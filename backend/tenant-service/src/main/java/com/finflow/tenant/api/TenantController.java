package com.finflow.tenant.api;

import com.finflow.tenant.application.TenantService;
import com.finflow.tenant.domain.Membership;
import com.finflow.tenant.domain.Tenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
class TenantController {

    private final TenantService service;

    TenantController(TenantService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = service.create(request.name(), request.slug(), request.plan(), request.adminUserId());
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenant.getId()))
                .body(TenantResponse.of(tenant));
    }

    @GetMapping("/current")
    TenantResponse current() {
        return TenantResponse.of(service.current());
    }

    @GetMapping("/{id}")
    TenantResponse get(@PathVariable UUID id) {
        return TenantResponse.of(service.get(id));
    }

    @GetMapping("/current/members")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    List<MemberResponse> members() {
        return service.members().stream().map(MemberResponse::of).toList();
    }

    record CreateTenantRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(min = 3, max = 40)
            @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                    message = "minuscules, chiffres et tirets simples uniquement") String slug,
            @NotNull Tenant.Plan plan,
            @NotBlank @Size(max = 64) String adminUserId) {
    }

    record TenantResponse(UUID id, String name, String slug, Tenant.Status status, Tenant.Plan plan,
                          Instant createdAt) {
        static TenantResponse of(Tenant t) {
            return new TenantResponse(t.getId(), t.getName(), t.getSlug(), t.getStatus(), t.getPlan(),
                    t.getCreatedAt());
        }
    }

    record MemberResponse(UUID id, String userId, Membership.Role role, Membership.Status status,
                          Instant createdAt) {
        static MemberResponse of(Membership m) {
            return new MemberResponse(m.getId(), m.getUserId(), m.getRole(), m.getStatus(), m.getCreatedAt());
        }
    }
}
