package gov.mirror.app;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/mirror")
public class PersonnelController {
    private final PersonnelService service;

    public PersonnelController(PersonnelService service) { this.service = service; }

    @GetMapping("/people")
    public Map<String, Object> people(@RequestParam(defaultValue = "") String search,
                                      @RequestParam(defaultValue = "") String status,
                                      @RequestParam(defaultValue = "") String organization,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "20") int limit) {
        return service.list(search, status, organization, offset, limit);
    }

    @GetMapping("/people/{id}")
    public Map<String, Object> person(@PathVariable String id) { return service.detail(id); }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() { return service.catalog(); }

    @PostMapping("/commands/{command}")
    public Object command(@PathVariable String command, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody Map<String, Object> input) {
        return service.command(command, input, key);
    }
}
