/**
 * Created by RIADVICE SUARL
 * Copyright 2020 | RIADVICE SUARL ( c )
 * All rights reserved. You may not use, distribute or modify
 * this code under its source or binary form without the express
 * authorization of RIADVICE SUARL. Contact : devops@riadvice.tn
 */

package com.riadvice.bbbmp4.services;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;

import com.riadvice.bbbmp4.data.FileNames;
import com.riadvice.bbbmp4.data.PlaybackFiles;
import com.riadvice.bbbmp4.entities.Recording;
import com.riadvice.bbbmp4.enums.VideoResolution;
import com.riadvice.bbbmp4.handlers.FFmpegProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;

@AutoConfigureBefore
public class VideoRecorder {

    private static final Logger logger = LoggerFactory.getLogger(VideoRecorder.class);

    private String sessionId;

    private RemoteWebDriver seleniumDriver;

    private Recording recording;

    private double syncDiff;

    private double overallDuration;

    private Path selenoidVideoDir;

    private Path videoDir;

    private String selenoidServiceUri;

    private String videoName;

    private Path videoLocation;

    public VideoRecorder(Recording recording, String selenoidServiceUri) {
        this.selenoidServiceUri = selenoidServiceUri;
        this.recording = recording;
    }

    public void recordViaSelenoid(Path recordingDirectory, Path videoLocation, Integer frameRate,
            String recordingBaseUrl) throws InterruptedException, IOException {
        this.videoLocation = videoLocation;
        this.videoName = recording.meetingId + ".mp4";
        this.selenoidVideoDir = recordingDirectory;
        this.videoDir = selenoidVideoDir.resolve(recording.meetingId);

        String formattedDuration = DurationFormatUtils.formatDurationHMS(recording.duration);
        Long sessionTimeout = this.getSessionTimeout(recording.duration);

        logger.info("Starting capabilities configuration for meeting recording with duration [{}]", formattedDuration);
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setCapability("browserName", "chrome");
        capabilities.setCapability("browserVersion", "86.0");
        capabilities.setCapability("selenoid:options",
                Map.<String, Object> of("enableVNC", true, "enableVideo", true, "enableLog", true, "logName",
                        recording.meetingId + ".log", "screenResolution",
                        VideoResolution.getScreenResolution(recording.quality), "sessionTimeout",
                        sessionTimeout.toString() + "s", "videoFrameRate", frameRate));

        logger.info("Creating Chrome options");
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation", "load-extension"));
        chromeOptions.addArguments("--start-fullscreen");
        chromeOptions.merge(capabilities);
        connectToSelenoid(chromeOptions);
        sessionId = seleniumDriver.getSessionId().toString();
        logger.info("Created Selenium session with id [{}]", sessionId);

        // @todo: detect the video URL type
        String recordingUrl = recordingBaseUrl + recording.meetingId + "?l=disabled";
        logger.info("Navigating to url [{}]", recordingUrl);
        seleniumDriver.navigate().to(recordingUrl);

        logger.info("Waiting 25 seconds for the play button visibility.");
        Thread.sleep(25000);

        logger.info(
                "The play button is now visible. Hiding it along with the playback bar and addition additional behaviours.");
        WebElement playbackButton = seleniumDriver.findElement(By.cssSelector("button[class=vjs-big-play-button]"));

        ((JavascriptExecutor) seleniumDriver)
                .executeScript("document.getElementsByClassName('player-wrapper')[0].style.padding='0'");
        ((JavascriptExecutor) seleniumDriver)
                .executeScript("document.getElementsByClassName('thumbnails-wrapper')[0].style.overflow='hidden'");
        ((JavascriptExecutor) seleniumDriver).executeScript(
                "document.getElementsByClassName('player-wrapper')[0].style['grid-template-rows']='60px 180px auto auto 0px'");
        // Hide shared notes button
        ((JavascriptExecutor) seleniumDriver)
                .executeScript("document.getElementsByClassName('icon-notes')[0].style.opacity='0'");
        // Add a handler when the recordings ends
        ((JavascriptExecutor) seleniumDriver).executeScript(
                "document.getElementsByClassName('video-js')[0].player.on('ended', function() {document.title = 'Recording Finished';});");
        playbackButton.click();

        // @important: must stay here, play will not work if placed before starting the
        // recording
        ((JavascriptExecutor) seleniumDriver)
                .executeScript("document.getElementsByClassName('video-js')[0].player.controls(false)");

        long recordingStarted = System.nanoTime();
        File recordMarkerFile = recordingDirectory.resolve("selenoid.record").toFile();
        try {
            recordMarkerFile.createNewFile();
            logger.info("Record marker file created at [{}]", recordingStarted);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            logger.error("An error occure during marker file creation", e);
        }

        logger.info("Putting the prcocessing on sleep again for {}.", formattedDuration);

        WebDriverWait playerEndWait = new WebDriverWait(seleniumDriver, sessionTimeout * 1000);
        playerEndWait.until(ExpectedConditions.titleIs("Recording Finished"));
        long alertVisibleTime = System.nanoTime();
        // @fixme : 250 diff is for HD and HD+
        overallDuration = ((alertVisibleTime - recordingStarted) / 1e6) - 100;
        syncDiff = overallDuration - recording.duration - 50;
        logger.info("Stop the playback after {}. Recording duration {}. Difference {}",
                (alertVisibleTime - recordingStarted) / 1e6, syncDiff, recording.duration - syncDiff);

        logger.info("Deleting record marker file and closing the session.");
        recordMarkerFile.delete();
        seleniumDriver.quit();

        renameFile();
        trimVideo();
        mixAudio();
    }

    private void renameFile() throws InterruptedException, IOException {
        File selenoidRecording = selenoidVideoDir.resolve(sessionId + ".mp4").toFile();
        File targetFile = videoDir.resolve(videoName).toFile();
        while (!selenoidRecording.exists()) {
            Thread.sleep(1000);
        }
        logger.info("Copying from {} to {}", selenoidRecording, targetFile);
        Files.copy(selenoidRecording.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        selenoidRecording.renameTo(targetFile);
    }

    private void trimVideo() {
        double trimStart = syncDiff / 1000d;
        double trimStop = overallDuration / 1000d;
        String source = videoDir.resolve(videoName).toAbsolutePath().toString();
        String target = videoDir.resolve(FileNames.VIDEO_TRIMMED).toAbsolutePath().toString();

        List<String> command = new ArrayList<String>();
        command.add("/usr/bin/ffmpeg");
        command.add("-loglevel");
        command.add("panic");
        command.add("-y");
        command.add("-i");
        command.add(source);
        command.add("-vf");
        command.add("trim=" + trimStart + ":" + trimStop);
        command.add("-threads");
        command.add("1");
        command.add(target);

        logger.info("Trimming the video [{}] and saving into [{}] to start from [{}] and stop at  [{}]", source, target,
                trimStart, trimStop);
        NuProcessBuilder pb = new NuProcessBuilder(command);
        FFmpegProcessHandler handler = new FFmpegProcessHandler();
        pb.setProcessListener(handler);
        NuProcess ffmpegTrimVideoProcess = pb.start();

        try {
            ffmpegTrimVideoProcess.waitFor(0, TimeUnit.SECONDS);
            // fileCopied = !handler.finishedWithError();
        } catch (InterruptedException e) {
            logger.error("InterruptedException while generating copying the file {} for meeting id {}");
        }
    }

    private void mixAudio() {
        File webcamsVideo = videoLocation.resolve(PlaybackFiles.WEBCAMS_MP4).toFile();
        if (!webcamsVideo.exists()) {
            webcamsVideo = videoLocation.resolve(PlaybackFiles.WEBCAMS_WEBM).toFile();
        }

        String source = videoDir.resolve(FileNames.VIDEO_TRIMMED).toAbsolutePath().toString();
        String target = videoDir.resolve(FileNames.VIDEO_MP4).toAbsolutePath().toString();
        String audioFilePath = webcamsVideo.getAbsolutePath().toString();

        List<String> command = new ArrayList<String>();
        command.add("/usr/bin/ffmpeg");
        command.add("-loglevel");
        command.add("panic");
        command.add("-y");
        command.add("-i");
        command.add(source);
        command.add("-i");
        command.add(audioFilePath);
        command.add("-map");
        command.add("0:v");
        command.add("-map");
        command.add("1:a");
        command.add("-c:v");
        command.add("copy");
        command.add("-shortest");
        command.add(target);

        // @todo: delete transitional files

        logger.info("Mixing the audio of recording [Source: {}, Target: {}, Webm: {}]", source, target, audioFilePath);
        NuProcessBuilder pb = new NuProcessBuilder(command);
        FFmpegProcessHandler handler = new FFmpegProcessHandler();
        pb.setProcessListener(handler);
        NuProcess ffmpegAudioMixProcess = pb.start();

        try {
            ffmpegAudioMixProcess.waitFor(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("InterruptedException while generating copying the file {} for meeting id {}");
        }
    }

    private Long getSessionTimeout(Long recordingTime) {
        return (long) ((recordingTime / 1000) * 1.5);
    }

    private void connectToSelenoid(MutableCapabilities capabilities) throws MalformedURLException {
        logger.info("Connection to selenoid driver.");
        this.seleniumDriver = new RemoteWebDriver(URI.create(selenoidServiceUri).toURL(), capabilities);
    }
}
