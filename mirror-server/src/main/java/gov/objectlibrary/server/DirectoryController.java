package gov.objectlibrary.server;

import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class DirectoryController {
    private final Accounts accounts;
    private final DirectoryService directory;
    DirectoryController(Accounts accounts,DirectoryService directory) {this.accounts=accounts;this.directory=directory;}
    @GetMapping("/organizations") List<DirectoryService.OrganizationView> organizations(Principal user) {
        return directory.organizations(accounts.actor(user.getName()));
    }
    @GetMapping("/people") DirectoryService.Page<DirectoryService.PersonView> people(Principal user,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String organization,
            @RequestParam(defaultValue="ACTIVE") String status,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return directory.people(accounts.actor(user.getName()),q,organization,status,page,size);
    }
    @GetMapping("/people/{id}") DirectoryService.PersonDetail person(Principal user,@PathVariable String id) {
        return directory.person(accounts.actor(user.getName()),id);
    }
}
