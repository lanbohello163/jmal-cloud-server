package com.jmal.clouddisk.video;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class VideoInfo {
    private Integer width;
    private Integer height;
    private Integer bitrate;
    private String format;
    private Integer duration;
    private String covertPath;

    public VideoInfo() {
        this.width = 0;
        this.height = 0;
        this.format = "";
        this.bitrate = 0;
        this.duration = 0;
    }

    public VideoInfo(String videoPath, int width, int height, String format, int bitrate, int duration, int rotation) {
        if (rotation == 90 || rotation == 270) {
            this.width = height;
            this.height = width;
        } else {
            this.width = width;
            this.height = height;
        }
        this.format = format;
        this.bitrate = bitrate;
        this.duration = duration;
        log.debug("\r\nvideoPath: {}, width: {}, height: {}, format: {}, bitrate: {}, duration: {}", videoPath, width, height, format, bitrate, duration);
    }

    public VideoInfoDO toVideoInfoDO() {
        VideoInfoDO videoInfoDO = new VideoInfoDO();
        if (this.bitrate > 0) {
            videoInfoDO.setBitrate(VideoInfoUtil.convertBitrateToReadableFormat(this.bitrate));
        }
        if (StrUtil.isNotBlank(this.format)) {
            videoInfoDO.setFormat(this.format);
        }
        if (this.duration > 0) {
            videoInfoDO.setDuration(VideoInfoUtil.formatTimestamp(this.duration, false));
        }
        if (this.height > 0 && this.width > 0) {
            videoInfoDO.setHeight(this.height);
            videoInfoDO.setWidth(this.width);
        }
        return videoInfoDO;
    }

}
