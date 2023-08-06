package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class CrptApi {

    private static final String URL = "https://<host:port>/api/v2/{extension}/rollout";
    private static final String OMS_ID_PARAM = "omsId=<Unique_OMS_identifier>";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String CONTENT_TYPE_VALUE = "application/json";
    private static final String CLIENT_TOKEN_HEADER = "clientToken";
    private static final String USERNAME_HEADER = "userName";
    private static final String USERNAME_VALUE = "user_name_value";
    private static final String ACCEPT_VALUE = "*/*";
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    private final int requestLimit;
    private static long interval;
    private static volatile int requestCounter;
    private final Validator validator = new Validator();
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    public CrptApi(TimeUnit timeUnit, int timeUnitAmount, int requestLimit) {
        this.requestLimit = requestLimit;
        this.interval = timeUnitAmount < 0 ? 0 : timeUnit.toMillis(timeUnitAmount);
    }

    private Model.responseReq createDocument(Model.Document document, String clientToken) throws Exceptions.InvalidDocumentException, Exceptions.NullTokenException, Exceptions.SendDocumentException {
        if (!this.validator.isValid(document))
            throw new Exceptions.InvalidDocumentException("Invalid value of the document");
        if (clientToken == null) throw new Exceptions.NullTokenException("Token can not be null");
        try {
            increaseRequestsCounter();
            return sendDocument(document, clientToken);
        } catch (Exception e) {
            throw new Exceptions.SendDocumentException("Failed to create a document: " + document);
        }
    }

    private synchronized void increaseRequestsCounter() throws InterruptedException {
        if (this.requestCounter >= this.requestLimit) wait();
        this.requestCounter++;
        executor.submit(timer);
    }

    private Model.responseReq sendDocument(Model.Document document, String clientToken) throws IOException, InterruptedException {
        final HttpClient client = HttpClient.newHttpClient();
        final Gson gson = new GsonBuilder().setDateFormat(DATE_FORMAT).create();

        System.out.println(gson.toJson(document));

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL + "?" + OMS_ID_PARAM))
                .header(CONTENT_TYPE_HEADER, CONTENT_TYPE_VALUE)
                .header(CLIENT_TOKEN_HEADER, clientToken)
                .header(USERNAME_HEADER, USERNAME_VALUE)
                .header(ACCEPT_HEADER, ACCEPT_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(document)))
                .build();

        final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        return gson.fromJson(response.body(), Model.responseReq.class);
    }

    Runnable timer = () -> {
        synchronized (this) {
            try {
                Thread.sleep(this.interval);

                this.requestCounter--;

                notify();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    };

    static class Model {

        @Data
        @AllArgsConstructor
        static class responseReq {
            @SerializedName("omsId")
            private String omsId;
            @SerializedName("reportId")
            private String reportId;
        }

        @Data
        @AllArgsConstructor
        static
        class Document {
            @NonNull
            String usageType;
            @NonNull
            String documentFormat;
            @NonNull
            String type;
            @NonNull
            String participantInn;
            @NonNull
            LocalDate productionDate;
            @NonNull
            List<Product> products;
            Produced produced;
            @SerializedName("import")
            Import anImport;
            String accompanyingDocument;
            String expDate;
            String expDate72;
            String capacity;

        }

        @Data
        static
        class Product {
            @NonNull
            String code;
            String certificateDocument;
            LocalDate certificateDocumentDate;
            String certificateDocumentNumber;
            @NonNull
            String tnvedCode;
        }

        @Data
        static
        class Produced {
            @NonNull
            String producerInn;
            @NonNull
            String ownerInn;
            @NonNull
            String productionType;
        }

        @Data
        static
        class Import {
            @NonNull
            LocalDate declarationDate;
            @NonNull
            String declarationNumber;
            @NonNull
            String customsCode;
            @NonNull
            Long decisionCode;
        }
    }

    static class Validator {

        private final int TNVED_CODE_LENGTH = 10;
        private final int MAX_YEARS_FOR_CERTIFICATE_DOCUMENT = 5;
        private final Pattern REGEX = Pattern.compile("^[0-9]+$");

        boolean isValid(Model.Document document) {
            return Arrays.stream(Enums.UsageType.values()).anyMatch(usageType -> usageType.getValue().equals(document.getUsageType()))
                    && Arrays.stream(Enums.DocumentFormat.values()).anyMatch(documentFormat -> documentFormat.getValue().equals(document.getDocumentFormat()))
                    && Arrays.stream(Enums.Type.values()).anyMatch(type -> type.getValue().equals(document.getType()))
                    && innIsValid(document.getParticipantInn())
                    && dateIsValid(document.getProductionDate())
                    && dateIsValid(document.getProducts())
                    && dateIsValid(document.getProduced(), document.getType())
                    && dateIsValid(document.getAnImport(), document.getType());
        }

        private boolean dateIsValid(List<Model.Product> products) {
            return products.stream()
                    .noneMatch(product ->
                            product.getTnvedCode().length() != TNVED_CODE_LENGTH
                                    || product.getTnvedCode().isBlank()
                                    || !certificateDocumentDateIsValid(product.getCertificateDocumentDate())
                                    || !certificateDocumentIsValid(product.getCertificateDocument())
                                    || product.getCode().isEmpty()
                                    || product.getCode().isBlank()
                    );
        }

        private boolean dateIsValid(Model.Produced produced, String type) {
            if (type.equals(Enums.Type.LP_GOODS_IMPORT_AUTO.getValue()) && produced == null) return true;
            return type.equals(Enums.Type.LP_INTRODUCE_GOODS_AUTO.getValue())
                    && produced.getProductionType().equals(Enums.ProductionType.OWN_PRODUCTION.getValue())
                    && innIsValid(produced.getOwnerInn())
                    && innIsValid(produced.getProducerInn());
        }

        private boolean dateIsValid(Model.Import importObj, String type) {
            if (type.equals(Enums.Type.LP_INTRODUCE_GOODS_AUTO.getValue()) && importObj == null) return true;
            return type.equals(Enums.Type.LP_GOODS_IMPORT_AUTO.getValue())
                    && importObj.getDecisionCode() > 0
                    && !importObj.getCustomsCode().isBlank()
                    && !importObj.getDeclarationNumber().isBlank()
                    && dateIsValid(importObj.getDeclarationDate());
        }

        private boolean dateIsValid(LocalDate date) {
            final LocalDate nowDate = LocalDate.now();
            return date.isAfter(ChronoLocalDate.from(nowDate.minusYears(MAX_YEARS_FOR_CERTIFICATE_DOCUMENT)))
                    && date.isBefore(nowDate);
        }

        private boolean innIsValid(String inn) {
            return (inn.length() == Enums.InnLength.TEN.getAmount() || inn.length() == Enums.InnLength.TWELVE.getAmount())
                    && !inn.isBlank()
                    && REGEX.matcher(inn).matches();
        }

        private boolean certificateDocumentDateIsValid(LocalDate date) {
            if (date == null) return true;
            return dateIsValid(date);
        }

        private boolean certificateDocumentIsValid(String certificateDocument) {
            if (certificateDocument == null) return true;
            return certificateDocument.equals(Enums.CertificateDocument.CERTIFICATE.getCode())
                    || certificateDocument.equals(Enums.CertificateDocument.DECLARATION.getCode());
        }
    }

    static class Enums {

        enum UsageType {
            SENT_TO_PRINTER("SENT_TO_PRINTER");

            private final String value;

            UsageType(String value) {
                this.value = value;
            }

            String getValue() {
                return this.value;
            }
        }

        enum DocumentFormat {
            MANUAL("MANUAL");

            private final String value;

            DocumentFormat(String value) {
                this.value = value;
            }

            String getValue() {
                return this.value;
            }
        }

        enum Type {
            LP_INTRODUCE_GOODS_AUTO("LP_INTRODUCE_GOODS_AUTO"),
            LP_GOODS_IMPORT_AUTO("LP_GOODS_IMPORT_AUTO");

            private final String value;

            Type(String value) {
                this.value = value;
            }

            String getValue() {
                return this.value;
            }
        }

        enum CertificateDocument {
            CERTIFICATE("1"),
            DECLARATION("2");

            private final String code;

            CertificateDocument(String code) {
                this.code = code;
            }

            String getCode() {
                return this.code;
            }
        }

        enum ProductionType {
            OWN_PRODUCTION("OWN_PRODUCTION");

            private final String value;

            ProductionType(String value) {
                this.value = value;
            }

            String getValue() {
                return this.value;
            }
        }

        enum InnLength {
            TEN(10),
            TWELVE(12);

            private final int amount;

            InnLength(int amount) {
                this.amount = amount;
            }

            int getAmount() {
                return this.amount;
            }
        }

    }

    static class Exceptions {

        static class InvalidDocumentException extends RuntimeException {
            public InvalidDocumentException(String message) {
                super(message);
            }
        }

        static class NullTokenException extends RuntimeException {
            public NullTokenException(String message) {
                super(message);
            }
        }

        static class SendDocumentException extends RuntimeException {
            public SendDocumentException(String message) {
                super(message);
            }
        }
    }

}