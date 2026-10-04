package com.example.toiletapi.report.dto;

import com.example.toiletapi.report.model.ReportStatus;

public record ReportStatusCount(ReportStatus status, long count) { }
