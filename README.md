
# mpd-downloader
Simple java application for downloading segments of a live on-going .mpd stream, and then a little tool for merging results.

# Usage
## Downloading .mpd

    java -jar mpd-downloader.jar download https://example.com/stream.mpd output-folder

Will download forever, when the stream is over or you've got all the data you want, you can press `Ctrl-C` to cancel downloading. You can tell the stream is over when you get continuous download errors for the latest m4s file.

## Merging files after download

    java -jar mpd-downloader.jar merge output-folder

Will now output output-folder.mp4 in the current directory.
