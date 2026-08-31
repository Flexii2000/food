package com.fherrmann.food.dto;

import java.time.LocalDate;

/**
 * Health/summary payload for the statusboard card. Shaped like the other cards' JSON
 * ({@code last_result}, an error slot) so the board's rendering stays uniform, but
 * served live from this app instead of written to a file by a cron job - the same way
 * the Sonntagsfrage card reads its own service.
 *
 * @param lastResult     {@code "ok"} or {@code "error"}
 * @param today          the day the figures below refer to
 * @param kcalConsumed   calories logged today
 * @param kcalTarget     the daily calorie goal
 * @param entriesToday   number of entries logged today
 * @param dishCount      size of the remembered dish library
 * @param lastEntryOn    most recent day with any entry, or {@code null} if there is none
 * @param lastError      error text if loading the data failed, otherwise {@code null}
 */
public record StatusInfo(
        String lastResult,
        LocalDate today,
        Double kcalConsumed,
        Double kcalTarget,
        Integer entriesToday,
        Integer dishCount,
        LocalDate lastEntryOn,
        String lastError) {
}
