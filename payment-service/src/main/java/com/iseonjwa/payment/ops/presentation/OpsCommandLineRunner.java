package com.iseonjwa.payment.ops.presentation;

import com.iseonjwa.payment.ops.application.OpsArguments;
import com.iseonjwa.payment.ops.application.OpsRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * {@code ops} 프로필로 기동했을 때 명령 하나를 실행하고 종료 코드를 남긴다(ADR 0028 결정 6). HTTP 엔드포인트가 아니라
 * 명령행 실행이다. 종료는 {@code PaymentApplication.main}이 {@link ExitCodeGenerator}의 값으로 한다.
 *
 * <p>Note: 이 빈은 {@code ops} 프로필에서만 만든다. 일반 결제 서비스와 테스트 컨텍스트에서는 기동 때 명령이 실행되지
 * 않는다. 실행기({@link OpsRunner})와 명령 빈은 프로필과 상관없이 있어 테스트가 직접 부른다.
 */
@Component
@Profile("ops")
public class OpsCommandLineRunner implements ApplicationRunner, ExitCodeGenerator {
    private final OpsRunner runner;
    private int exitCode = OpsRunner.EXIT_ERROR;

    public OpsCommandLineRunner(OpsRunner runner) {
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments applicationArguments) {
        var options = new LinkedHashMap<String, List<String>>();
        for (var name : applicationArguments.getOptionNames()) {
            options.put(name, applicationArguments.getOptionValues(name));
        }
        var arguments = new OpsArguments(applicationArguments.getNonOptionArgs(), options);
        exitCode = runner.run(arguments, System.out);
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
