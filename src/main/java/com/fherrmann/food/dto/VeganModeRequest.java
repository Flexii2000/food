package com.fherrmann.food.dto;

/**
 * Der Schalter "Veganer Modus".
 *
 * @param enabled an oder aus. Ein Objekt statt eines blossen Wahrheitswerts, damit
 *                ein fehlender Rumpf als Fehler auffaellt und nicht still "aus" heisst
 */
public record VeganModeRequest(Boolean enabled) {
}
