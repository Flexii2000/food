package com.fherrmann.food.featurerequest;

import com.fherrmann.food.featurerequest.FeatureRequestDtos.AppOption;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.DraftJob;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.DraftRequest;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.FeatureRequestView;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.Features;
import com.fherrmann.food.featurerequest.FeatureRequestDtos.NewFeatureRequest;
import com.fherrmann.food.security.HealthUsers;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

/**
 * Die Schnittstelle der Seite {@code fherrmann.com/feature-requests/}. nginx reicht
 * den Pfad unveraendert an diesen Dienst weiter, deshalb liegt alles unter
 * {@code /feature-requests/}. Angemeldet wird wie ueberall hier - Healthy-Token als
 * Cookie oder Bearer, oder der Privat-Cookie der Eigentuemerin.
 */
@RestController
@RequestMapping("/feature-requests/api")
public class FeatureRequestController {

    private final FeatureRequestService service;
    private final StoryDraftJobs drafts;
    private final HealthUsers users;

    public FeatureRequestController(FeatureRequestService service, StoryDraftJobs drafts, HealthUsers users) {
        this.service = service;
        this.drafts = drafts;
        this.users = users;
    }

    @GetMapping("/features")
    public Features features(Principal principal) {
        String user = principal.getName();
        return new Features(user, users.isOwner(user), drafts.isAvailable(),
                Arrays.stream(FeatureApp.values()).map(AppOption::of).toList());
    }

    /** Startet einen Entwurf und antwortet sofort mit der Auftragsnummer. */
    @PostMapping("/drafts")
    public ResponseEntity<DraftJob> startDraft(@RequestBody DraftRequest request, Principal principal) {
        return ResponseEntity.accepted()
                .body(drafts.start(principal.getName(),
                        FeatureApp.parse(request == null ? null : request.app()),
                        request == null ? null : request.text()));
    }

    @GetMapping("/drafts/{jobId}")
    public DraftJob draft(@PathVariable String jobId, Principal principal) {
        return drafts.status(principal.getName(), jobId);
    }

    @GetMapping("/requests")
    public List<FeatureRequestView> requests(Principal principal) {
        return service.list(principal.getName());
    }

    @PostMapping("/requests")
    public ResponseEntity<FeatureRequestView> create(@RequestBody NewFeatureRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.getName(), request));
    }

    @GetMapping("/requests/{id}")
    public FeatureRequestView request(@PathVariable String id, Principal principal) {
        return service.get(principal.getName(), id);
    }

    /** Nur fuer die Eigentuemerin; alle anderen bekommen 404. */
    @DeleteMapping("/requests/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Principal principal) {
        service.delete(principal.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
