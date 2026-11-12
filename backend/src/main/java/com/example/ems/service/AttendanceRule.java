package com.example.ems.service;

import java.time.LocalTime;

record AttendanceRule(long id, LocalTime start, LocalTime end, int lateWarning, int earlyWarning,
                      int inException, int outException, LocalTime earliestIn, LocalTime latestOut) {
    enum Result { NORMAL, WARNING, EXCEPTION, CLOSED }

    private int lastInSecond() {
        return start.toSecondOfDay() + (lateWarning + inException) * 60;
    }

    private int firstOutSecond() {
        return end.toSecondOfDay() - (earlyWarning + outException) * 60;
    }

    LocalTime lastIn() {
        return LocalTime.ofSecondOfDay(Math.min(86399, lastInSecond()));
    }

    LocalTime firstOut() {
        return LocalTime.ofSecondOfDay(Math.max(0, firstOutSecond()));
    }

    void validate() {
        if (!start.isBefore(end) || earliestIn.isAfter(start) || latestOut.isBefore(end)
                || lateWarning < 0 || earlyWarning < 0 || inException < 0 || outException < 0
                || lateWarning > 1439 || earlyWarning > 1439 || inException > 1439 || outException > 1439
                || lastInSecond() >= firstOutSecond()) {
            throw new IllegalArgumentException("考勤时间或分钟范围无效，签到窗口必须早于签退窗口");
        }
    }

    Result checkIn(LocalTime time) {
        int second = time.toSecondOfDay();
        if (time.isBefore(earliestIn) || second > lastInSecond()) return Result.CLOSED;
        if (second <= start.toSecondOfDay()) return Result.NORMAL;
        return second <= start.toSecondOfDay() + lateWarning * 60 ? Result.WARNING : Result.EXCEPTION;
    }

    Result checkOut(LocalTime time) {
        int second = time.toSecondOfDay();
        if (second < firstOutSecond() || second > latestOut.toSecondOfDay()) return Result.CLOSED;
        if (second >= end.toSecondOfDay()) return Result.NORMAL;
        return second >= end.toSecondOfDay() - earlyWarning * 60 ? Result.WARNING : Result.EXCEPTION;
    }

    int deviation(LocalTime time, boolean in) {
        int seconds = in ? time.toSecondOfDay() - start.toSecondOfDay()
                : end.toSecondOfDay() - time.toSecondOfDay();
        return Math.max(0, (seconds + 59) / 60);
    }
}
