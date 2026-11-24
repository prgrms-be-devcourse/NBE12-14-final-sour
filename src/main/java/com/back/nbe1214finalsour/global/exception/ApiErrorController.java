package com.back.nbe1214finalsour.global.exception;

import com.back.nbe1214finalsour.global.response.ApiResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서블릿 컨테이너의 {@code /error} 디스패치를 API 명세서 §1 형식({code, message, data})으로 응답한다.
 *
 * <p>{@link GlobalExceptionHandler}는 컨트롤러 안의 예외만 처리한다. 필터에서 난 예외나
 * {@code response.sendError()}는 컨테이너가 {@code /error}로 다시 보내므로 여기서 처리한다.
 * 이 빈이 있으면 Spring Boot 기본 {@code BasicErrorController}(Whitelabel HTML·기본 JSON)는 만들어지지 않는다.
 *
 * <p>예외 로그는 컨테이너가 남기므로 여기서 다시 남기지 않는다. 응답에는 예외 정보를 넣지 않는다.
 */
@RestController
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ApiErrorController implements ErrorController {

    @RequestMapping
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        HttpStatusCode status = resolveStatus(request);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(ErrorCode.commonFor(status)));
    }

    private HttpStatusCode resolveStatus(HttpServletRequest request) {
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (!(statusCode instanceof Integer value)) {
            // 오류 디스패치가 아니라 클라이언트가 /error를 직접 호출한 경우: 공개 경로가 아니므로 없는 경로로 본다.
            return HttpStatus.NOT_FOUND;
        }
        if (value < 400 || value > 599) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatusCode.valueOf(value);
    }
}
