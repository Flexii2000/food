package com.fherrmann.food.controller;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DayTotal;
import com.fherrmann.food.dto.DeviceRegistration;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.Features;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.QuickCaptureRequest;
import com.fherrmann.food.dto.QuickCaptureJob;
import com.fherrmann.food.dto.StatusInfo;
import com.fherrmann.food.dto.TargetsRequest;
import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.push.DeviceTokens;
import com.fherrmann.food.service.FoodService;
import com.fherrmann.food.service.QuickCaptureJobs;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/food")
public class FoodController {

    private final FoodService service;
    private final QuickCaptureJobs quickCaptureJobs;

    private final DeviceTokens devices;

    public FoodController(FoodService service, QuickCaptureJobs quickCaptureJobs,
                          DeviceTokens devices) {
        this.devices = devices;
        this.service = service;
        this.quickCaptureJobs = quickCaptureJobs;
    }

    /** Targets, totals and entries for one day; defaults to today. */
    @GetMapping("/day")
    public DaySummary day(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.day(date);
    }

    /** The remembered dish library, most recently used first. */
    @GetMapping("/dishes")
    public List<Dish> dishes() {
        return service.dishes();
    }

    @PostMapping("/dishes")
    public ResponseEntity<Dish> createDish(@RequestBody DishRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDish(request));
    }

    @PutMapping("/dishes/{id}")
    public Dish updateDish(@PathVariable String id, @RequestBody DishRequest request) {
        return service.updateDish(id, request);
    }

    @DeleteMapping("/dishes/{id}")
    public ResponseEntity<Void> deleteDish(@PathVariable String id) {
        service.deleteDish(id);
        return ResponseEntity.noContent().build();
    }

    /** Welche optionalen Funktionen dieser Server anbietet. */
    @GetMapping("/features")
    public Features features() {
        return new Features(service.quickCaptureAvailable());
    }

    /**
     * Startet eine Schnellerfassung und gibt <b>sofort</b> eine Auftragsnummer
     * zurueck. Das Ergebnis wird ueber {@link #quickCaptureStatus} abgeholt.
     *
     * <p>Die Auswertung braucht bis zu einer Minute, weil sie Naehrwerte im Netz
     * nachschlaegt. Eine HTTP-Anfrage so lange offenzuhalten ist gegen jede
     * Zwischenstation mit eigenem Timeout empfindlich - genau daran ist es
     * vorher gescheitert, und zwar ohne verwertbaren Fehler.
     */
    @PostMapping("/quick-capture")
    public ResponseEntity<QuickCaptureJob> quickCapture(@RequestBody QuickCaptureRequest request) {
        return ResponseEntity.accepted().body(quickCaptureJobs.start(request));
    }

    /** Stand einer Schnellerfassung: laeuft noch, fertig, oder fehlgeschlagen. */
    @GetMapping("/quick-capture/{id}")
    public QuickCaptureJob quickCaptureStatus(@PathVariable String id) {
        return quickCaptureJobs.status(id);
    }

    /** Logs an amount of a dish and returns the refreshed day. */
    @PostMapping("/entries")
    public ResponseEntity<DaySummary> addEntry(@RequestBody NewEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addEntry(request));
    }

    @DeleteMapping("/entries/{id}")
    public DaySummary deleteEntry(@PathVariable String id) {
        return service.deleteEntry(id);
    }

    @GetMapping("/targets")
    public Nutrients targets() {
        return service.targets();
    }

    @PutMapping("/targets")
    public Nutrients updateTargets(@RequestBody TargetsRequest request) {
        return service.updateTargets(request);
    }

    /**
     * Per-day totals for a date range. Read cross-site by the weight tracker to draw
     * its kcal overlay, hence the narrow shape - see {@code food.cors.allowed-origins}.
     */
    @GetMapping("/daily")
    public List<DayTotal> daily(
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.dailyTotals(from, to);
    }

    /**
     * Live status for the card on status.fherrmann.com, also read cross-site. Served by
     * the app itself rather than written to a file by a cron job the way most of the
     * board's cards are: the numbers are already at hand here, and a response arriving
     * at all is the health check.
     */
    /**
     * Meldet ein Geraet fuer Benachrichtigungen an.
     *
     * <p>Die App ruft das bei jedem Start auf: iOS vergibt die Kennung
     * gelegentlich neu, und eine veraltete faellt sonst erst auf, wenn eine
     * Benachrichtigung ins Leere geht.
     */
    @PostMapping("/devices")
    public ResponseEntity<Void> registerDevice(@RequestBody DeviceRegistration request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        devices.add(request.token().trim());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public StatusInfo status() {
        return service.status();
    }
}
