package gov.objectlibrary.server;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class TagController {
    private final Accounts accounts;
    private final TagService tags;
    private final PersonTagCommands personTags;
    TagController(Accounts accounts, TagService tags, PersonTagCommands personTags) {
        this.accounts = accounts;
        this.tags = tags;
        this.personTags = personTags;
    }
    @GetMapping("/tags") List<TagService.TagView> list(Principal principal) {return tags.tags(accounts.actor(principal.getName()));}
    @PostMapping("/tags") Map<String,Object> create(Principal principal,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create input) {
        return tags.create(accounts.actor(principal.getName()),new TagService.CreateTag(input.code(),input.name().strip(),input.parentId(),input.dimension(),input.description()),key);
    }
    @PutMapping("/tags/{id}") Map<String,Object> edit(Principal principal,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Edit input) {
        return tags.edit(accounts.actor(principal.getName()),id,new TagService.EditTag(input.name().strip(),input.description(),input.status(),input.expectedVersion()),key);
    }
    @GetMapping("/people/{id}/tags") List<TagService.AssignmentView> person(Principal principal,@PathVariable String id) {
        return tags.personTags(accounts.actor(principal.getName()),id);
    }
    @GetMapping("/people/{id}/tags/{tag}/history") List<TagService.TagChange> history(Principal principal,@PathVariable String id,@PathVariable String tag) {
        return tags.history(accounts.actor(principal.getName()),id,tag);
    }
    @GetMapping("/people/{id}/tags/{tag}/contributions")
    List<PersonTagCommands.ContributionView> contributions(Principal principal, @PathVariable String id,
                                                          @PathVariable String tag) {
        return personTags.contributions(accounts.actor(principal.getName()), id, tag);
    }

    @PostMapping("/tags/{id}/assignments") Map<String,Object> assign(Principal principal,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Assign input) {
        return tags.assign(accounts.actor(principal.getName()),id,new TagService.AssignmentCommand(input.personIds(),input.expectedVersions(),input.operation(),input.note(),input.tagVersion()),key);
    }
    record Create(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,39}") String code,@NotBlank @Size(max=80) String name,
                  @NotNull @Size(max=100) String parentId,@Pattern(regexp="PERSON|WORK") @NotNull String dimension,
                  @NotNull @Size(max=1000) String description) {}
    record Edit(@NotBlank @Size(max=80) String name,@NotNull @Size(max=1000) String description,
                @NotNull @Pattern(regexp="ACTIVE|INACTIVE") String status,@Positive long expectedVersion) {}
    record Assign(@NotEmpty @Size(max=100) List<@NotBlank @Size(max=100) String> personIds,
                  @NotNull @Size(max=100) Map<String,@NotNull @PositiveOrZero Long> expectedVersions,
                  @NotNull @Pattern(regexp="ADD|REMOVE|RESTORE") String operation,@NotNull @Size(max=1000) String note,@Positive long tagVersion) {}
}
