package com.strider.chat.model.request;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

public record CursorQuery(
        Integer size,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        LocalDateTime cursorCreatedAt,
        String cursorId,
        ScrollDirection direction
) { }
