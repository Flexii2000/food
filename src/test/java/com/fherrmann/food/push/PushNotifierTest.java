package com.fherrmann.food.push;

import com.fherrmann.food.dto.QuickCapturePreview;
import com.fherrmann.food.model.Meal;
import com.fherrmann.food.model.Nutrients;
import com.fherrmann.food.push.DeviceTokens.Environment;
import com.fherrmann.food.push.DeviceTokens.IosDevice;
import com.fherrmann.food.push.DeviceTokens.Platform;
import com.fherrmann.food.push.PushNotifier.Delivery;
import com.fherrmann.food.security.HealthUsers;
import com.fherrmann.food.security.UserFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Wohin eine Mitteilung geht: an die Geraete genau einer Person, jedes iPhone an den
 * APNs-Rechner seiner Umgebung - und was mit einer Kennung passiert, die Apple ablehnt.
 * Die beiden Dienste sind Attrappen, die Geraeteliste ist die echte.
 */
class PushNotifierTest {

    @TempDir
    Path tempDir;

    private ApnsClient apns;
    private FcmClient fcm;
    private DeviceTokens devices;
    private PushNotifier notifier;

    @BeforeEach
    void setUp() {
        HealthUsers users = new HealthUsers("felix", "torben:0123456789abcdef0123456789abcdef");
        devices = new DeviceTokens(tempDir.resolve("devices.json").toString(),
                tempDir.resolve("devices-production.json").toString(),
                tempDir.resolve("devices-android.json").toString(),
                new ObjectMapper(), new UserFiles(users), users);
        apns = mock(ApnsClient.class);
        fcm = mock(FcmClient.class);
        when(apns.isConfigured()).thenReturn(true);
        when(fcm.isConfigured()).thenReturn(true);
        when(apns.send(any(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ApnsClient.Result.DELIVERED);
        when(fcm.deliver(anyString(), anyMap())).thenReturn(FcmClient.Result.DELIVERED);
        notifier = new PushNotifier(apns, fcm, devices, users);
    }

    /** Jedes iPhone bekommt die Nachricht am Rechner seiner Umgebung, mit Art und Daten. */
    @Test
    void everyIphoneIsReachedThroughItsOwnEnvironment() {
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-xcode");
        devices.add("felix", Platform.IOS, Environment.PRODUCTION, "apns-testflight");

        Delivery delivery = notifier.send("felix", "friend-nudge", "Person A", "stupst dich an",
                Map.of("friendshipId", "f-1"));

        assertThat(delivery).isEqualTo(new Delivery(2, 0));
        verify(apns).send(Environment.SANDBOX, "apns-xcode", "Person A", "stupst dich an", "friend-nudge",
                Map.of("friendshipId", "f-1"));
        verify(apns).send(Environment.PRODUCTION, "apns-testflight", "Person A", "stupst dich an", "friend-nudge",
                Map.of("friendshipId", "f-1"));
    }

    /** Nur die Geraete der gemeinten Person - auf beiden Plattformen. */
    @Test
    void onlyThePersonsOwnDevicesAreAddressed() {
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-felix");
        devices.add("felix", Platform.ANDROID, "fcm-felix");
        devices.add("torben", Platform.ANDROID, "fcm-torben");

        Delivery delivery = notifier.send("torben", "streak-warning", "Serie mit Person A",
                "noch 2.300 Schritte", null);

        assertThat(delivery).isEqualTo(new Delivery(0, 1));
        verify(apns, never()).send(any(), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(fcm).deliver(eq("fcm-torben"), anyMap());
        verify(fcm, never()).deliver(eq("fcm-felix"), anyMap());
    }

    /** Android bekommt alles als Datennachricht: erst die Art, dann die Daten, dann Titel und Text. */
    @Test
    void androidGetsADataMessageWithKindDataTitleAndBody() {
        devices.add("torben", Platform.ANDROID, "fcm-torben");
        Map<String, String> data = new LinkedHashMap<>();
        data.put("friendshipId", "f-1");
        // Was die Nachricht selbst setzt, laesst sich von aussen nicht ueberschreiben.
        data.put("kind", "quick-capture");
        data.put("title", "gefaelscht");
        data.put("aps", "{}");

        notifier.send("torben", "friend-nudge", "Person A", "stupst dich an", data);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> message = ArgumentCaptor.forClass(Map.class);
        verify(fcm).deliver(eq("fcm-torben"), message.capture());
        assertThat(message.getValue()).containsExactly(
                Map.entry("kind", "friend-nudge"),
                Map.entry("friendshipId", "f-1"),
                Map.entry("title", "Person A"),
                Map.entry("body", "stupst dich an"));
    }

    /**
     * BadDeviceToken heisst meist: falsche Umgebung. Genau ein Versuch am anderen
     * Rechner - nimmt der die Kennung an, steht sie ab jetzt dort.
     */
    @Test
    void aTokenRejectedInItsEnvironmentMovesToTheOneThatAcceptsIt() {
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-testflight");
        when(apns.send(eq(Environment.SANDBOX), eq("apns-testflight"), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ApnsClient.Result.BAD_TOKEN);

        Delivery delivery = notifier.send("felix", "chat-answer", "Healthy", "Deine Antwort ist da.", Map.of());

        assertThat(delivery.ios()).isEqualTo(1);
        verify(apns).send(eq(Environment.PRODUCTION), eq("apns-testflight"), anyString(), anyString(),
                eq("chat-answer"), anyMap());
        assertThat(devices.ios("felix")).containsExactly(new IosDevice("apns-testflight", Environment.PRODUCTION));

        // Beim naechsten Mal geht sie direkt an den richtigen Rechner - kein zweiter Fehlversuch.
        notifier.send("felix", "chat-answer", "Healthy", "Deine Antwort ist da.", Map.of());
        verify(apns, times(1)).send(eq(Environment.SANDBOX), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(apns, times(2)).send(eq(Environment.PRODUCTION), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    /** Lehnen beide Rechner sie ab, ist die Kennung tot. */
    @Test
    void aTokenRejectedByBothEnvironmentsIsForgotten() {
        devices.add("felix", Platform.IOS, Environment.PRODUCTION, "apns-tot");
        when(apns.send(any(), eq("apns-tot"), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ApnsClient.Result.BAD_TOKEN);

        Delivery delivery = notifier.send("felix", "chat-answer", "Healthy", "Deine Antwort ist da.", Map.of());

        assertThat(delivery.ios()).isZero();
        verify(apns, times(2)).send(any(), eq("apns-tot"), anyString(), anyString(), anyString(), anyMap());
        assertThat(devices.ios("felix")).isEmpty();
    }

    /** Gibt der zweite Rechner keine Auskunft (Netz), bleibt die Kennung, wo sie ist. */
    @Test
    void anInconclusiveSecondAttemptKeepsTheToken() {
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-1");
        when(apns.send(eq(Environment.SANDBOX), eq("apns-1"), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ApnsClient.Result.BAD_TOKEN);
        when(apns.send(eq(Environment.PRODUCTION), eq("apns-1"), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ApnsClient.Result.KEPT);

        assertThat(notifier.send("felix", "chat-answer", "Healthy", "Deine Antwort ist da.", Map.of()).ios()).isZero();

        assertThat(devices.ios("felix")).containsExactly(new IosDevice("apns-1", Environment.SANDBOX));
    }

    /** 410: das Geraet hat die App nicht mehr - kein zweiter Versuch, die Kennung fliegt. */
    @Test
    void anUnregisteredTokenIsForgottenWithoutASecondAttempt() {
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-weg");
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-da");
        when(apns.send(any(), eq("apns-weg"), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ApnsClient.Result.GONE);

        Delivery delivery = notifier.send("felix", "chat-answer", "Healthy", "Deine Antwort ist da.", Map.of());

        assertThat(delivery.ios()).isEqualTo(1);
        verify(apns, times(1)).send(any(), eq("apns-weg"), anyString(), anyString(), anyString(), anyMap());
        assertThat(devices.ios("felix")).containsExactly(new IosDevice("apns-da", Environment.SANDBOX));
    }

    /** Ein Aussetzer (Netz, Ueberlast) kostet keine Kennung. */
    @Test
    void aTransientFailureKeepsTheToken() {
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-1");
        devices.add("felix", Platform.ANDROID, "fcm-1");
        when(apns.send(any(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(ApnsClient.Result.KEPT);
        when(fcm.deliver(anyString(), anyMap())).thenReturn(FcmClient.Result.KEPT);

        assertThat(notifier.send("felix", "chat-answer", "Healthy", "Deine Antwort ist da.", Map.of()))
                .isEqualTo(new Delivery(0, 0));

        assertThat(devices.ios("felix")).hasSize(1);
        assertThat(devices.all("felix", Platform.ANDROID)).containsExactly("fcm-1");
    }

    @Test
    void anAndroidTokenFirebaseNoLongerKnowsIsForgotten() {
        devices.add("torben", Platform.ANDROID, "fcm-alt");
        when(fcm.deliver(eq("fcm-alt"), anyMap())).thenReturn(FcmClient.Result.GONE);

        assertThat(notifier.send("torben", "friend-nudge", "Person A", "stupst dich an", null).android()).isZero();

        assertThat(devices.all("torben", Platform.ANDROID)).isEmpty();
    }

    /** Ohne Schluessel faellt der jeweilige Weg weg - und niemand wird gefragt. */
    @Test
    void anUnconfiguredChannelIsSkipped() {
        devices.add("felix", Platform.IOS, Environment.SANDBOX, "apns-1");
        devices.add("felix", Platform.ANDROID, "fcm-1");
        when(apns.isConfigured()).thenReturn(false);
        when(fcm.isConfigured()).thenReturn(false);

        assertThat(notifier.send("felix", "chat-answer", "Healthy", "Deine Antwort ist da.", Map.of()))
                .isEqualTo(new Delivery(0, 0));

        verify(apns, never()).send(any(), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(fcm, never()).deliver(anyString(), anyMap());
    }

    // --- die bisherigen Mitteilungen bleiben, wie sie waren -------------------------

    /** Die fertige Schnellerfassung: Android bekommt genau die Datennachricht von immer. */
    @Test
    void aFinishedQuickCaptureStillLooksTheSameOnAndroid() {
        devices.add("torben", Platform.ANDROID, "fcm-torben");
        devices.add("felix", Platform.IOS, "apns-felix");
        QuickCapturePreview preview = new QuickCapturePreview(false, null, "Gericht A",
                new Nutrients(95, 6, 12, 2.5), 400.0, 400.0, Meal.LUNCH, Map.of(), "geschaetzt", null);

        notifier.quickCaptureFinished("torben", "job-1", "done", preview, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> message = ArgumentCaptor.forClass(Map.class);
        verify(fcm).deliver(eq("fcm-torben"), message.capture());
        assertThat(message.getValue()).containsExactly(
                Map.entry("kind", "quick-capture"),
                Map.entry("jobId", "job-1"),
                Map.entry("status", "done"),
                Map.entry("title", "Vorschlag ist fertig"),
                Map.entry("body", "Gericht A – antippen zum Übernehmen."));
        verify(apns, never()).send(any(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    /** Auf dem iPhone geht sie an die bisherige (Sandbox-)Kennung, mit Titel und Text von immer. */
    @Test
    void aFailedQuickCaptureReachesTheOwnersIphoneAsBefore() {
        devices.add("felix", Platform.IOS, "apns-felix");

        notifier.quickCaptureFinished("felix", "job-2", "failed", null, null);

        verify(apns).send(Environment.SANDBOX, "apns-felix", "Auswertung fehlgeschlagen",
                "Bitte noch einmal versuchen.", "quick-capture", Map.of("jobId", "job-2", "status", "failed"));
    }

    /** Die neue App-Version geht an jedes Android-Geraet, egal wessen - und an kein iPhone. */
    @Test
    void anAppReleaseReachesEveryAndroidDevice() {
        devices.add("felix", Platform.ANDROID, "fcm-felix");
        devices.add("torben", Platform.ANDROID, "fcm-torben");
        devices.add("felix", Platform.IOS, "apns-felix");

        notifier.appReleased(62, "1.11");

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("kind", "app-update");
        expected.put("versionCode", "62");
        expected.put("versionName", "1.11");
        expected.put("title", "Neue Version 1.11");
        expected.put("body", "Antippen zum Installieren.");
        verify(fcm).deliver("fcm-felix", expected);
        verify(fcm).deliver("fcm-torben", expected);
        verify(apns, never()).send(any(), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(fcm).isConfigured();
        verifyNoMoreInteractions(fcm);
    }
}
