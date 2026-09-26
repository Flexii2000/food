package com.fherrmann.food.dto;

/**
 * Die neueste veroeffentlichte Android-App, wie die App sie zum Vergleich abfragt.
 *
 * @param versionCode die fortlaufende Nummer aus dem Build - nur sie wird verglichen
 * @param versionName die Anzeige ("1.2")
 * @param sizeBytes   Groesse der APK, fuer die Fortschrittsanzeige beim Laden
 * @param sha256      Pruefsumme der APK, hexadezimal - gerechnet hier aus genau der
 *                    Datei, die ausgeliefert wird, damit Angabe und Datei nie
 *                    auseinanderlaufen
 */
public record AndroidReleaseInfo(int versionCode, String versionName, long sizeBytes, String sha256) {
}
