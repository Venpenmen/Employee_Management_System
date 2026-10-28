package com.example.ems.web;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;

@Controller
public class SafeErrorController implements ErrorController {
    @RequestMapping("/error")
    public Object error(HttpServletRequest request,HttpServletResponse response){
        Object raw=request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status=raw instanceof Number n?n.intValue():500;
        Object cause=request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        Throwable error=cause instanceof Throwable t?t:new ResponseStatusException(HttpStatusCode.valueOf(status));
        UserErrors.Failure failure=UserErrors.describe(error);
        String path=String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        if(path.startsWith("/api/")){
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            var body=new LinkedHashMap<String,Object>();
            body.put("code",failure.status());body.put("msg",failure.message());body.put("data",null);
            return ResponseEntity.status(failure.status()).body(body);
        }
        response.setContentType("text/html;charset=UTF-8");
        var page=new ModelAndView("error-page");
        page.setStatus(HttpStatusCode.valueOf(failure.status()));
        page.addObject("message",failure.message());page.addObject("path",path);
        return page;
    }
}
