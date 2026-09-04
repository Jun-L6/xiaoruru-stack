package beer.xiaoruru.site;

import beer.xiaoruru.setting.SiteSettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@ControllerAdvice
public class GlobalModelAdvice {
    private final SiteSettingsService settings;

    public GlobalModelAdvice(SiteSettingsService settings) {
        this.settings = settings;
    }

    @ModelAttribute("site")
    public Map<String, String> siteSettings() {
        return settings.all();
    }

    @ModelAttribute("currentPath")
    public String currentPath(jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.substring(request.getContextPath().length());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ModelAndView responseStatus(ResponseStatusException exception) {
        if (exception.getStatusCode().value() == NOT_FOUND.value()) {
            ModelAndView view = new ModelAndView("error/404", NOT_FOUND);
            view.addObject("site", settings.all());
            return view;
        }
        return new ModelAndView(null, exception.getStatusCode());
    }
}
