package com.fherrmann.food.dto;

import java.time.LocalDate;

/**
 * Ein Cappuccino mehr ({@code POST /api/food/cappuccino}).
 *
 * @param date der Tag; ohne Angabe heute
 * @param id   die Kennung der App fuer den Eintrag (kleingeschriebene UUID, wie bei
 *             {@code POST /entries}) - ein nachgesendetes "+" zaehlt dann nicht doppelt
 */
public record CappuccinoRequest(LocalDate date, String id) {
}
