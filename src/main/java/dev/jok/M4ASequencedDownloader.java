package dev.jok;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.URL;
import java.util.Arrays;

public class M4ASequencedDownloader {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java mpd-downloader.jar <download/merge>");
            return;
        }

        if (args[0].equals("download")) {
            download(args);
        } else if (args[0].equals("merge")) {
            merge(args);
        } else {
            System.out.println("Usage: java mpd-downloader.jar <download/merge>");
        }
    }

    private static void download(String[] args) {
        if (args.length <= 2) {
            System.out.println("Usage: java mpd-downloader.jar download <mpd-url> <output-dir>");
            return;
        }

        String mpdUrl = args[1];
        String outputDir = args[2];

        if (!outputDir.endsWith("/")) {
            outputDir += "/";
        }

        // make directories
        new java.io.File(outputDir + "audio/").mkdirs();
        new java.io.File(outputDir + "video/").mkdirs();

        MPDInfo audioInfo = fetchMPDInfo(mpdUrl, "audio");
        MPDInfo videoInfo = fetchMPDInfo(mpdUrl, "video");

        if (audioInfo == null && videoInfo == null) {
            System.out.println("Failed to fetch MPD information.");
            return;
        }

        String finalOutputDir = outputDir;

        if (audioInfo != null) {
            System.out.println("Audio bandwidth: " + audioInfo.bandwidth);
            new Thread(() -> {
                try {
                    downloadSegments(audioInfo, finalOutputDir + "audio/");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }

        if (videoInfo != null) {
            System.out.println("Video bandwidth: " + videoInfo.width + "x" + videoInfo.height + ", " + videoInfo.bandwidth);
            new Thread(() -> {
                try {
                    downloadSegments(videoInfo, finalOutputDir + "video/");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }).start();
        }
    }

    private static void merge(String[] args) {
        if (args.length <= 1) {
            System.out.println("Usage: java mpd-downloader.jar merge <directory1> <directory2> <directory3> ...");
            return;
        }

        String[] directories = Arrays.stream(args).skip(1).toArray(String[]::new);

        for (String directory : directories) {
            System.out.println();
            System.out.println("Merging: " + directory);

            // combine all the files in the audio and video directories
            String audioDir = directory + "/audio/";
            String videoDir = directory + "/video/";

            // merge audio
            String audioOutput = directory + "/audio.m4a";
            String videoOutput = directory + "/video.mp4";
            mergeFiles(audioDir, audioOutput, "audio");
            mergeFiles(videoDir, videoOutput, "video");

            System.out.println("Running ffmpeg to combine audio and video");

            // ffmpeg combine audio and video
            try {
                ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-i", audioOutput, "-i", videoOutput, "-c", "copy", directory + ".mp4");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try(InputStream stdout = p.getInputStream()) {
                    stdout.transferTo(System.out);
                }

                p.waitFor();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }


    }

    private static void mergeFiles(String dirPath, String outputFileName, String contentType) {
        File[] files = new File(dirPath).listFiles();
        if (files == null) {
            System.out.println("No files found in: " + dirPath);
            return;
        }

        try (BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(outputFileName), 1024 * 1024 * 512)) {
            // 8mb buffer
            byte[] buffer = new byte[1024 * 1024 * 8];

            // filter out non .m4s files
            files = Arrays.stream(files).filter(f -> f.getName().endsWith(".m4s")).toArray(File[]::new);

            // sort by file name
            Arrays.sort(files, (f1, f2) -> {
                // init file should always be first
                if (f1.getName().equals("init.m4s")) {
                    return -1;
                } else if (f2.getName().equals("init.m4s")) {
                    return 1;
                }

                // sort by segment id number
                int n1 = Integer.parseInt(f1.getName().replace(".m4s", ""));
                int n2 = Integer.parseInt(f2.getName().replace(".m4s", ""));
                return Integer.compare(n1, n2);
            });

            System.out.println();
            System.out.println("Merging " + files.length + " " + contentType + " files");

            int done = 0;
            int lastPercent = -1;
            for (File file : files) {
                writeFileToStream(file, fos, buffer);

                int percent = (int) (((double) ++done / files.length) * 100);
                if (percent != lastPercent) {
                    System.out.println("Merged " + done + " files (" + percent + "%)");
                    lastPercent = percent;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeFileToStream(File file, OutputStream fos, byte[] buffer) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new URL(file.toURI().toString()).openStream())) {
            int bytesRead;
            while ((bytesRead = bis.read(buffer, 0, buffer.length)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }

    private static void downloadSegments(MPDInfo mpdInfo, String outputDir) throws InterruptedException {
        int startSequence = mpdInfo.startNumber;

        // Download initialization segment
        String initFileUrl = mpdInfo.baseUrl + mpdInfo.init;
        System.out.println("Downloading: " + initFileUrl);
        Exception exception = downloadFile(initFileUrl, outputDir + "init.m4s");
        if (exception != null) {
            System.out.println("Failed to download: " + initFileUrl);
            System.out.println("Error: " + exception.getMessage());
            return;
        }

        for (int i = startSequence; true; i++) {
            String fileName = i + ".m4s";
            String fileUrl = mpdInfo.baseUrl + mpdInfo.media.replace("$Number$", String.valueOf(i));

            int failedAttempts = 0;
            while (true) {
                IOException error = downloadFile(fileUrl, outputDir + fileName);
                if (error != null) {
                    if (++failedAttempts >= 5) {
                        System.out.println("Failed to download: " + fileName);
                        System.out.println("Error: " + error.getMessage());
                        break;
                    }

                    System.out.println("Waiting for stream to catch up on " + mpdInfo.contentType + "...");
                    Thread.sleep(5000);
                } else {
                    System.out.println("Downloaded " + mpdInfo.contentType + ": " + fileName);
                    break;
                }
            }
        }
    }

    private static IOException downloadFile(String fileUrl, String outputFilePath) {
        // Check if the file exists
        if (new java.io.File(outputFilePath).exists()) {
            System.out.println("File already exists: " + outputFilePath);
            return null;
        }

        try (BufferedInputStream in = new BufferedInputStream(new URL(fileUrl).openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(outputFilePath)) {

            byte[] dataBuffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }

        } catch (IOException e) {
            return e;
        }

        return null;
    }

    private static MPDInfo fetchMPDInfo(String mpdUrl, String contentType) {
        try {
            Document doc = Jsoup.connect(mpdUrl).get();
            String baseUrl = getBaseUrl(mpdUrl);
            Elements adaptationSets = doc.getElementsByTag("AdaptationSet");
            MPDInfo highestBandwidthInfo = null;
            int highestBandwidth = 0;

            for (Element adaptationSet : adaptationSets) {
                if (contentType.equals(adaptationSet.attr("contentType"))) {
                    Elements representations = adaptationSet.getElementsByTag("Representation");
                    for (Element representation : representations) {
                        int bandwidth = Integer.parseInt(representation.attr("bandwidth"));
                        if (bandwidth > highestBandwidth) {
                            highestBandwidth = bandwidth;
                            Element segmentTemplate = representation.getElementsByTag("SegmentTemplate").first();
                            String init = segmentTemplate.attr("initialization");
                            int startNumber = Integer.parseInt(segmentTemplate.attr("startNumber"));
                            String media = segmentTemplate.attr("media");
                            int width = representation.hasAttr("width") ? Integer.parseInt(representation.attr("width")) : 0;
                            int height = representation.hasAttr("height") ? Integer.parseInt(representation.attr("height")) : 0;

                            highestBandwidthInfo = new MPDInfo(baseUrl, media, contentType, init, startNumber, width, height, bandwidth);
                        }
                    }
                }
            }
            return highestBandwidthInfo;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String getBaseUrl(String mpdUrl) {
        int lastSlashIndex = mpdUrl.lastIndexOf('/');
        return lastSlashIndex != -1 ? mpdUrl.substring(0, lastSlashIndex + 1) : "";
    }

    private static class MPDInfo {
        String baseUrl;
        String media;
        String contentType;
        String init;
        int startNumber;
        int width;
        int height;
        int bandwidth;

        MPDInfo(String baseUrl, String media, String contentType, String init, int startNumber, int width, int height, int bandwidth) {
            this.baseUrl = baseUrl;
            this.media = media;
            this.contentType = contentType;
            this.init = init;
            this.startNumber = startNumber;
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
        }
    }
}
