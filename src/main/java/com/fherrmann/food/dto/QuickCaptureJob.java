package com.fherrmann.food.dto;

/**
 * Stand einer laufenden oder fertigen Schnellerfassung.
 *
 * <p>Die Auswertung startet eine Claude-Session, die Naehrwerte nachschlaegt und
 * dafuer bis zu einer Minute braucht. Eine HTTP-Anfrage so lange offenzuhalten
 * funktioniert zwar, ist aber gegen jede Zwischenstation mit eigenem Timeout
 * empfindlich - nginx, der WireGuard-Tunnel, die vorgelagerte VPS. Genau daran
 * ist es vorher gescheitert, und zwar ohne verwertbaren Fehler: nginx setzte
 * bei HTTP/2 den Stream zurueck, statt einen Status zu schicken.
 *
 * <p>Deshalb antwortet der Start sofort mit einer Auftragsnummer, und die
 * Oberflaeche fragt den Stand ab. Jede einzelne Anfrage ist damit kurz.
 *
 * @param id      Auftragsnummer, mit der der Stand abgefragt wird
 * @param status  {@code running}, {@code done} oder {@code failed}
 * @param preview das Ergebnis, sobald {@code status = done}
 * @param error   die Fehlermeldung, sobald {@code status = failed}
 * @param elapsedSeconds wie lange der Auftrag schon laeuft bzw. gelaufen ist
 */
public record QuickCaptureJob(
        String id,
        String status,
        QuickCapturePreview preview,
        String error,
        long elapsedSeconds) {

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
}
