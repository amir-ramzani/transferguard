package io.github.nulled.transferguard.common;

import java.util.List;

public record PageResponse<T>(int page, int size, long totalItems, List<T> items) {}
