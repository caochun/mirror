package gov.objectlibrary.server;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Lets the single Spring HTTP endpoint serve both Vite-built applications and their client routes. */
@Controller
class SpaController {
    @GetMapping({"/", "/workbench", "/people", "/people/{id}", "/tags"})
    String admin() { return "forward:/index.html"; }

    @GetMapping("/r/**")
    String receiver() { return "forward:/receiver.html"; }
}
