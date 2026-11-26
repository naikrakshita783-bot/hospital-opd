package com.hospital.opd;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
@EnableJpaRepositories(considerNestedRepositories = true)
@SpringBootApplication
public class OpdApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpdApplication.class, args);
    }

    // ===================== ENTITIES =====================

    public enum TokenStatus { WAITING, IN_CONSULTATION, DONE }

    @Entity(name = "Doctor")
    @Table(name = "doctor")
    public static class Doctor {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        public String name;
        public String specialization;
        public String roomNo;
        public boolean available = true;
    }

    @Entity(name = "Patient")
    @Table(name = "patient")
    public static class Patient {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        public String name;
        public Integer age;
        public String phone;
        @Column(length = 500)
        public String symptoms;
    }

    @Entity(name = "Token")
    @Table(name = "token")
    public static class Token {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long id;
        public int tokenNumber;
        public LocalDate tokenDate;
        public LocalDateTime createdAt;
        @Enumerated(EnumType.STRING)
        public TokenStatus status = TokenStatus.WAITING;
        @ManyToOne(optional = false)
        public Patient patient;
        @ManyToOne(optional = false)
        public Doctor doctor;
    }

    // ===================== DTOs =====================

    public record RegisterRequest(String name, Integer age, String phone, String symptoms, Long doctorId) {}

    public record TokenDto(Long id, int tokenNumber, String patientName, Long doctorId,
                           String doctorName, String roomNo, String status, long aheadOfYou) {}

    // ===================== REPOSITORIES =====================

    public interface DoctorRepo extends JpaRepository<Doctor, Long> {}

    public interface PatientRepo extends JpaRepository<Patient, Long> {}

    public interface TokenRepo extends JpaRepository<Token, Long> {
        List<Token> findByTokenDateOrderByTokenNumberAsc(LocalDate date);

        @Query("select coalesce(max(t.tokenNumber), 0) from Token t where t.doctor.id = :doctorId and t.tokenDate = :date")
        int maxNumber(@Param("doctorId") Long doctorId, @Param("date") LocalDate date);

        long countByDoctorIdAndTokenDateAndStatusAndTokenNumberLessThan(
                Long doctorId, LocalDate date, TokenStatus status, int tokenNumber);

        boolean existsByDoctorIdAndTokenDateAndStatus(Long doctorId, LocalDate date, TokenStatus status);
    }

    // ===================== SERVICES =====================

    @Service
    public static class DoctorService {
        private final DoctorRepo doctors;

        public DoctorService(DoctorRepo doctors) { this.doctors = doctors; }

        public List<Doctor> getAll() { return doctors.findAll(); }

        public Doctor add(Doctor d) {
            if (d.name == null || d.name.isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor name is required");
            d.id = null;
            d.available = true;
            return doctors.save(d);
        }

        public Doctor setAvailable(Long id, boolean value) {
            Doctor d = doctors.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found"));
            d.available = value;
            return doctors.save(d);
        }
    }

    @Service
    public static class TokenService {
        private final TokenRepo tokens;
        private final PatientRepo patients;
        private final DoctorRepo doctors;

        public TokenService(TokenRepo tokens, PatientRepo patients, DoctorRepo doctors) {
            this.tokens = tokens;
            this.patients = patients;
            this.doctors = doctors;
        }

        @Transactional
        public TokenDto register(RegisterRequest req) {
            if (req.name() == null || req.name().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient name is required");
            if (req.doctorId() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please select a doctor");
            Doctor doctor = doctors.findById(req.doctorId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Doctor not found"));
            if (!doctor.available)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Doctor is not available today");

            Patient p = new Patient();
            p.name = req.name().trim();
            p.age = req.age();
            p.phone = req.phone();
            p.symptoms = req.symptoms();
            p = patients.save(p);

            LocalDate today = LocalDate.now();
            Token t = new Token();
            t.patient = p;
            t.doctor = doctor;
            t.tokenNumber = tokens.maxNumber(doctor.id, today) + 1;
            t.tokenDate = today;
            t.createdAt = LocalDateTime.now();
            t.status = TokenStatus.WAITING;
            return toDto(tokens.save(t));
        }

        public List<TokenDto> getToday() {
            return tokens.findByTokenDateOrderByTokenNumberAsc(LocalDate.now()).stream().map(this::toDto).toList();
        }

        public TokenDto getOne(Long id) { return toDto(find(id)); }

        @Transactional
        public TokenDto updateStatus(Long id, String value) {
            TokenStatus newStatus;
            try {
                newStatus = TokenStatus.valueOf(value);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status: " + value);
            }
            Token t = find(id);
            if (newStatus == TokenStatus.IN_CONSULTATION && t.status != TokenStatus.IN_CONSULTATION
                    && tokens.existsByDoctorIdAndTokenDateAndStatus(t.doctor.id, t.tokenDate, TokenStatus.IN_CONSULTATION))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This doctor already has a patient in consultation. Mark that one Done first.");
            t.status = newStatus;
            return toDto(tokens.save(t));
        }

        private Token find(Long id) {
            return tokens.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found"));
        }

        private TokenDto toDto(Token t) {
            long ahead = t.status == TokenStatus.WAITING
                    ? tokens.countByDoctorIdAndTokenDateAndStatusAndTokenNumberLessThan(
                            t.doctor.id, t.tokenDate, TokenStatus.WAITING, t.tokenNumber)
                    : 0;
            return new TokenDto(t.id, t.tokenNumber, t.patient.name, t.doctor.id,
                    t.doctor.name, t.doctor.roomNo, t.status.name(), ahead);
        }
    }

    @Service
    public static class GeminiService {
        private final DoctorRepo doctors;
        private final RestTemplate rest = new RestTemplate();

        @Value("${gemini.api.key:}")
        private String apiKey;
        @Value("${gemini.model:gemini-2.5-flash}")
        private String model;

        public GeminiService(DoctorRepo doctors) { this.doctors = doctors; }

        public String suggestDoctor(String symptoms) {
            if (apiKey == null || apiKey.isBlank())
                return "AI suggestion is not configured (GEMINI_API_KEY is missing).";
            List<Doctor> list = doctors.findAll().stream().filter(d -> d.available).toList();
            if (list.isEmpty()) return "No doctors are available right now.";

            StringBuilder sb = new StringBuilder();
            for (Doctor d : list) sb.append("- ").append(d.name).append(" (").append(d.specialization).append(")\n");
            String prompt = "You help hospital patients choose an OPD doctor. Available doctors:\n" + sb
                    + "\nPatient symptoms: " + symptoms
                    + "\n\nPick the single best doctor from the list above and give a one-sentence reason. "
                    + "Do not give a diagnosis. If the symptoms sound like an emergency, say to go to the emergency department. "
                    + "Keep the answer under 3 sentences, plain text only.";
            try {
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                h.set("x-goog-api-key", apiKey);
                Map<String, Object> body = Map.of("contents",
                        List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
                String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
                JsonNode r = rest.postForObject(url, new HttpEntity<>(body, h), JsonNode.class);
                String text = r == null ? "" : r.path("candidates").path(0).path("content")
                        .path("parts").path(0).path("text").asText("");
                return text.isBlank() ? "AI could not suggest a doctor. Please choose manually." : text.trim();
            } catch (Exception e) {
                return "AI service is unavailable right now. Please choose a doctor manually.";
            }
        }
    }

    // ===================== CONTROLLER =====================

    @RestController
    @RequestMapping("/api")
    public static class ApiController {
        private final DoctorService doctorService;
        private final TokenService tokenService;
        private final GeminiService geminiService;

        public ApiController(DoctorService d, TokenService t, GeminiService g) {
            this.doctorService = d;
            this.tokenService = t;
            this.geminiService = g;
        }

        @GetMapping("/doctors")
        public List<Doctor> doctors() { return doctorService.getAll(); }

        @PostMapping("/doctors")
        public Doctor addDoctor(@RequestBody Doctor d) { return doctorService.add(d); }

        @PutMapping("/doctors/{id}/availability")
        public Doctor availability(@PathVariable Long id, @RequestParam boolean value) {
            return doctorService.setAvailable(id, value);
        }

        @PostMapping("/tokens")
        public TokenDto register(@RequestBody RegisterRequest r) { return tokenService.register(r); }

        @GetMapping("/tokens/today")
        public List<TokenDto> today() { return tokenService.getToday(); }

        @GetMapping("/tokens/{id}")
        public TokenDto token(@PathVariable Long id) { return tokenService.getOne(id); }

        @PutMapping("/tokens/{id}/status")
        public TokenDto status(@PathVariable Long id, @RequestParam String value) {
            return tokenService.updateStatus(id, value);
        }

        @PostMapping("/ai/suggest")
        public Map<String, String> suggest(@RequestBody Map<String, String> body) {
            String s = body.getOrDefault("symptoms", "");
            if (s.isBlank()) return Map.of("suggestion", "Please enter your symptoms first.");
            return Map.of("suggestion", geminiService.suggestDoctor(s));
        }
    }
@Bean
public CommandLineRunner seedDoctors(DoctorRepo repo) {
    return args -> {
        if (repo.count() == 0) {
            repo.save(newDoctor("Dr. Anil Rao", "Cardiologist", "3"));
            repo.save(newDoctor("Dr. Meera Shah", "General Physician", "1"));
            repo.save(newDoctor("Dr. Sunil Nair", "Orthopedic", "5"));
            repo.save(newDoctor("Dr. Priya Das", "Dermatologist", "2"));
        }
    
    }; 
}


private static Doctor newDoctor(String name, String specialization, String roomNo) {
    Doctor d = new Doctor();
    d.name = name;
    d.specialization = specialization;
    d.roomNo = roomNo;
    return d;
}
}


