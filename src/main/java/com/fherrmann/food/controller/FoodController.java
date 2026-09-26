package com.fherrmann.food.controller;

import com.fherrmann.food.dto.DaySummary;
import com.fherrmann.food.dto.DayAverage;
import com.fherrmann.food.dto.DayTotal;
import com.fherrmann.food.dto.DeviceRegistration;
import com.fherrmann.food.dto.DishRequest;
import com.fherrmann.food.dto.Features;
import com.fherrmann.food.dto.NewEntryRequest;
import com.fherrmann.food.dto.QuickCaptureRequest;
import com.fherrmann.food.dto.QuickCaptureJob;
import com.fherrmann.food.dto.StatusInfo;
import com.fherrmann.food.dto.TargetsRequest;
import com.fherrmann.food.dto.UpdateEntryRequest;
import com.fherrmann.food.model.Dish;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.push.DeviceTokens;
import com.fherrmann.food.push.DeviceTokens.Platform;
import com.fherrmann.food.service.DetailedNutrition;
import com.fherrmann.food.service.FoodService;
import com.fherrmann.food.service.QuickCaptureAccess;
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

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * Every endpoint works on the diary of the person behind the token - the auth filter puts
 * their name into the {@link Principal}.
 */
@RestController
@RequestMapping("/api/food")
public class FoodController {

    private final FoodService service;
    private final QuickCaptureJobs quickCaptureJobs;
    private final QuickCaptureAccess quickCaptureAccess;
    private final DetailedNutrition detailedNutrition;

    private final DeviceTokens devices;

    public FoodController(FoodService service, QuickCaptureJobs quickCaptureJobs,
                          QuickCaptureAccess quickCaptureAccess, DetailedNutrition detailedNutrition,
                          DeviceTokens devices) {
        this.devices = devices;
        this.service = service;
        this.quickCaptureJobs = quickCaptureJobs;
        this.quickCaptureAccess = quickCaptureAccess;
        this.detailedNutrition = detailedNutrition;
    }

    /** Targets, totals and entries for one day; defaults to today. */
    @GetMapping("/day")
    public DaySummary day(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Principal principal) {
        return service.day(principal.getName(), date);
    }

    /** The remembered dish library, most recently used first. */
    @GetMapping("/dishes")
    public List<Dish> dishes(Principal principal) {
        return service.dishes(principal.getName());
    }

    @PostMapping("/dishes")
    public ResponseEntity<Dish> createDish(@RequestBody DishRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDish(principal.getName(), request));
    }

    @PutMapping("/dishes/{id}")
    public Dish updateDish(@PathVariable String id, @RequestBody DishRequest request, Principal principal) {
        return service.updateDish(principal.getName(), id, request);
    }

    @DeleteMapping("/dishes/{id}")
    public ResponseEntity<Void> deleteDish(@PathVariable String id, Principal principal) {
        service.deleteDish(principal.getName(), id);
        return ResponseEntity.noContent().build();
    }

    /** Welche optionalen Funktionen dieser Server dieser Person anbietet - und wer sie ist. */
    @GetMapping("/features")
    public Features features(Principal principal) {
        String user = principal.getName();
        return new Features(service.quickCaptureAvailable() && quickCaptureAccess.allows(user), user,
                detailedNutrition.isDetailed(user));
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
    public ResponseEntity<QuickCaptureJob> quickCapture(@RequestBody QuickCaptureRequest request,
                                                        Principal principal) {
        return ResponseEntity.accepted().body(quickCaptureJobs.start(principal.getName(), request));
    }

    /** Stand einer Schnellerfassung: laeuft noch, fertig, oder fehlgeschlagen. */
    @GetMapping("/quick-capture/{id}")
    public QuickCaptureJob quickCaptureStatus(@PathVariable String id, Principal principal) {
        return quickCaptureJobs.status(principal.getName(), id);
    }

    /** Logs an amount of a dish and returns the refreshed day. */
    @PostMapping("/entries")
    public ResponseEntity<DaySummary> addEntry(@RequestBody NewEntryRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addEntry(principal.getName(), request));
    }

    /** Menge, Mahlzeit oder Tag eines Eintrags berichtigen - das Gericht bleibt. */
    @PutMapping("/entries/{id}")
    public DaySummary updateEntry(@PathVariable String id, @RequestBody UpdateEntryRequest request,
                                  Principal principal) {
        return service.updateEntry(principal.getName(), id, request);
    }

    @DeleteMapping("/entries/{id}")
    public DaySummary deleteEntry(@PathVariable String id, Principal principal) {
        return service.deleteEntry(principal.getName(), id);
    }

    @GetMapping("/targets")
    public Nutrients targets(Principal principal) {
        return service.targets(principal.getName());
    }

    @PutMapping("/targets")
    public Nutrients updateTargets(@RequestBody TargetsRequest request, Principal principal) {
        return service.updateTargets(principal.getName(), request);
    }

    /**
     * Per-day totals for a date range. Read cross-site by the weight tracker to draw
     * its kcal overlay, hence the narrow shape - see {@code food.cors.allowed-origins}.
     */
    @GetMapping("/daily")
    public List<DayTotal> daily(
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return service.dailyTotals(principal.getName(), from, to);
    }

    /**
     * Gleitendes 7-Tage-Mittel der kcal je Tag (siehe {@link FoodService#dailyAverages}).
     * Wie {@code /daily} cross-site vom Weight Tracker gelesen - beide Verlaufsdiagramme
     * zeigen das Mittel statt der Tageswerte.
     */
    @GetMapping("/daily-average")
    public List<DayAverage> dailyAverage(
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Principal principal) {
        return service.dailyAverages(principal.getName(), from, to);
    }

    /**
     * Meldet ein Geraet fuer Benachrichtigungen an - fuer die Person zum Token.
     *
     * <p>Die Apps rufen das bei jedem Start auf: iOS und Firebase vergeben die
     * Kennung gelegentlich neu, und eine veraltete faellt sonst erst auf, wenn
     * eine Benachrichtigung ins Leere geht.
     */
    @PostMapping("/devices")
    public ResponseEntity<Void> registerDevice(@RequestBody DeviceRegistration request, Principal principal) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Platform platform;
        try {
            platform = Platform.parse(request.platform());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        devices.add(principal.getName(), platform, request.token().trim());
        return ResponseEntity.noContent().build();
    }

    /**
     * Live status for the card on status.fherrmann.com, also read cross-site. Served by
     * the app itself rather than written to a file by a cron job the way most of the
     * board's cards are: the numbers are already at hand here, and a response arriving
     * at all is the health check.
     */
    @GetMapping("/status")
    public StatusInfo status(Principal principal) {
        return service.status(principal.getName());
    }
}
