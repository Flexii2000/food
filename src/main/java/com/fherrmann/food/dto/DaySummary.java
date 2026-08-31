package com.fherrmann.food.dto;

import com.fherrmann.food.model.FoodEntry;
import com.fherrmann.food.model.Nutrients;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the day view needs in one response: the goals, what has been eaten
 * against them, and the entries it adds up from.
 *
 * @param date      the day being shown
 * @param targets   daily goals
 * @param consumed  sum of all entries on that day
 * @param remaining {@code targets - consumed}; negative once a goal is exceeded, which
 *                  the UI shows rather than clamping - being over is the thing worth seeing
 * @param entries   the day's entries, oldest first
 */
public record DaySummary(
        LocalDate date,
        Nutrients targets,
        Nutrients consumed,
        Nutrients remaining,
        List<FoodEntry> entries) {
}
