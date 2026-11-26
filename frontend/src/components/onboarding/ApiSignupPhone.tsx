import React, { useContext, useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { PhoneShellVariantContext } from '../PhoneShell.tsx';
import { useApiClient } from '../../api/ApiProvider';
import {
  checkNicknameAvailability,
  login,
  nicknameProblem,
  NICKNAME_RULE,
  passwordProblem,
  PASSWORD_RULE_TEXT,
  requestEmailCode,
  signup,
  SIGNUP_ERROR_CODES,
  SIGNUP_SERVER_RULES,
  verifyEmailCode,
  CODE_PATTERN,
  type NamedRef,
  type SignupResponse,
} from '../../api/auth';
import { errorMessage, fieldErrorMap, isApiError } from '../../api/errors';
import { useDepartments } from '../../api/universities';
import { REAL_UNIVERSITIES } from '../../data/universitiesData.ts';
import { CodeStep, DoneStep, EmailStep, PasswordStep, PhoneFrame, ProfileStep, type OnboardingSchool } from './OnboardingSteps.tsx';

// Sign-up against the backend (SCRUM-151): email → code → password → nickname and department → POST /auth/signup,
// then a login with the same email and password (SignupResponse: "클라이언트는 이어서 로그인 API를 호출한다", SCRUM-152).
// The mock onboarding (OnboardingPhone) stays for VITE_API_MODE=mock.

/** Badge look from the frontend table; a school the table lacks still gets a plain badge. */
export function schoolBadge(university: NamedRef): OnboardingSchool {
  const known = REAL_UNIVERSITIES.find((school) => school.id === university.id);
  return known
    ? { name: known.name, firstSyllable: known.firstSyllable, badgeColor: known.badgeColor }
    : { name: university.name, firstSyllable: [...university.name][0] ?? '?', badgeColor: '#8b5327' };
}

type Stage =
  | { step: 'email' }
  | { step: 'code'; email: string; university: NamedRef; issuedAt: number; expiresAt: number; resendAt: number; attemptsLeft: number }
  | { step: 'password'; email: string; university: NamedRef; signupToken: string; tokenExpiresAt: number }
  | { step: 'profile'; email: string; university: NamedRef; signupToken: string; tokenExpiresAt: number; password: string }
  /** `signedIn`: the automatic login right after sign-up worked. */
  | { step: 'done'; result: SignupResponse; signedIn: boolean };

const NEW_CODE_NOTICE = '새 인증 코드를 보냈습니다. 이전 코드는 더 이상 쓸 수 없어요.';
const NICKNAME_AVAILABLE = '사용할 수 있는 닉네임입니다.';
const NICKNAME_TAKEN = '이미 사용 중인 닉네임입니다.';

const codeOf = (error: unknown) => (isApiError(error) ? error.code : null);

interface ApiSignupPhoneProps {
  onGoToCampus: () => void;
  /** Link for members who already signed up. */
  onGoToLogin?: () => void;
}

export const ApiSignupPhone: React.FC<ApiSignupPhoneProps> = ({ onGoToCampus, onGoToLogin }) => {
  const client = useApiClient();
  const fullShell = useContext(PhoneShellVariantContext) === 'full';
  const [stage, setStage] = useState<Stage>({ step: 'email' });
  const [emailInput, setEmailInput] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [nickname, setNickname] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [nicknameStatus, setNicknameStatus] = useState<{ ok: boolean; message: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now);

  const codeRequest = useMutation({ mutationFn: (email: string) => requestEmailCode(client, email) });
  const verify = useMutation({ mutationFn: (input: { email: string; code: string }) => verifyEmailCode(client, input.email, input.code) });
  const nicknameCheck = useMutation({ mutationFn: (value: string) => checkNicknameAvailability(client, value) });
  const enroll = useMutation({ mutationFn: (input: Parameters<typeof signup>[1]) => signup(client, input) });
  const signIn = useMutation({ mutationFn: (input: { email: string; password: string }) => login(client, input.email, input.password) });
  const pending = codeRequest.isPending || verify.isPending || nicknameCheck.isPending || enroll.isPending || signIn.isPending;

  const universityId = stage.step === 'profile' ? stage.university.id : null;
  const departments = useDepartments(universityId);

  // The code's validity and the resend wait count down on screen, at the mock onboarding's 250ms pace.
  const ticking = stage.step === 'code' || stage.step === 'password' || stage.step === 'profile';
  useEffect(() => {
    if (!ticking) return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [ticking]);

  const show = (message: string | null, success: string | null = null) => {
    setError(message);
    setNotice(success);
  };

  /** Back to the first step, e.g. when the sign-up token expired; what was typed for the account is dropped. */
  const restart = (message: string | null) => {
    setStage({ step: 'email' });
    setPassword('');
    setConfirm('');
    setNickname('');
    setDepartmentId('');
    setNicknameStatus(null);
    show(message);
  };

  const tokenExpiresAt = stage.step === 'password' || stage.step === 'profile' ? stage.tokenExpiresAt : null;
  useEffect(() => {
    if (tokenExpiresAt !== null && now >= tokenExpiresAt && !pending) {
      restart('가입 가능 시간이 지났어요. 이메일 인증을 다시 해 주세요.');
    }
  // restart uses only stable state setters.
  }, [tokenExpiresAt, now, pending]);

  const sendCode = (email: string, resend: boolean) => {
    codeRequest.mutate(email, {
      onSuccess: (response) => {
        const issuedAt = Date.now();
        setNow(issuedAt);
        setStage({
          step: 'code',
          email,
          university: response.university,
          issuedAt,
          expiresAt: issuedAt + response.expiresInSeconds * 1000,
          resendAt: issuedAt + SIGNUP_SERVER_RULES.resendCooldownSeconds * 1000,
          attemptsLeft: SIGNUP_SERVER_RULES.maxCodeAttempts,
        });
        show(null, resend ? NEW_CODE_NOTICE : null);
      },
      onError: (failure) => show(fieldErrorMap(failure).email ?? errorMessage(failure)),
    });
  };

  let content: React.ReactNode = null;
  if (stage.step === 'email') {
    content = (
      <EmailStep
        email={emailInput}
        onEmailChange={(value) => {
          setEmailInput(value);
          setError(null);
        }}
        onRequestCode={() => {
          const email = emailInput.trim();
          if (!email) {
            show('학교 이메일 주소를 입력해 주세요.');
            return;
          }
          sendCode(email, false);
        }}
        error={error}
        pending={pending}
        sampleEmails={[]}
        onGoToLogin={onGoToLogin}
      />
    );
  } else if (stage.step === 'code') {
    content = (
      <CodeStep
        key={stage.issuedAt}
        email={stage.email}
        school={schoolBadge(stage.university)}
        remainingSeconds={Math.max(0, Math.ceil((stage.expiresAt - now) / 1000))}
        attemptsLeft={stage.attemptsLeft}
        resendWaitSeconds={Math.max(0, Math.ceil((stage.resendAt - now) / 1000))}
        error={error}
        notice={notice}
        pending={pending}
        onVerify={(code) => {
          if (!CODE_PATTERN.test(code)) {
            show('6자리 숫자를 모두 입력해 주세요.');
            return;
          }
          const requestedAt = Date.now();
          verify.mutate({ email: stage.email, code }, {
            onSuccess: (response) => {
              setNow(Date.now());
              setStage({ step: 'password', email: stage.email, university: response.university, signupToken: response.signupToken, tokenExpiresAt: requestedAt + response.expiresInSeconds * 1000 });
              show(null);
            },
            onError: (failure) => {
              const errorCode = codeOf(failure);
              // The server counts wrong codes but does not say how many are left; count the same way here.
              if (errorCode === SIGNUP_ERROR_CODES.invalidCode || errorCode === SIGNUP_ERROR_CODES.attemptsExceeded) {
                const attemptsLeft = errorCode === SIGNUP_ERROR_CODES.invalidCode ? Math.max(0, stage.attemptsLeft - 1) : 0;
                setStage({ ...stage, attemptsLeft });
                show(errorCode === SIGNUP_ERROR_CODES.invalidCode ? `${errorMessage(failure)} 남은 시도 ${attemptsLeft}회` : errorMessage(failure));
                return;
              }
              show(fieldErrorMap(failure).code ?? errorMessage(failure));
            },
          });
        }}
        onResend={() => sendCode(stage.email, true)}
        onEditEmail={() => {
          setEmailInput(stage.email);
          setStage({ step: 'email' });
          show(null);
        }}
      />
    );
  } else if (stage.step === 'password') {
    content = (
      <PasswordStep
        school={schoolBadge(stage.university)}
        email={stage.email}
        password={password}
        confirm={confirm}
        onPasswordChange={(value) => { setPassword(value); setError(null); }}
        onConfirmChange={(value) => { setConfirm(value); setError(null); }}
        ruleText={PASSWORD_RULE_TEXT}
        error={error}
        onNext={() => {
          if (Date.now() >= stage.tokenExpiresAt) { restart('가입 가능 시간이 지났어요. 이메일 인증을 다시 해 주세요.'); return; }
          const problem = passwordProblem(password, confirm);
          if (problem) {
            show(problem);
            return;
          }
          setStage({ ...stage, step: 'profile', password });
          show(null);
        }}
      />
    );
  } else if (stage.step === 'profile') {
    const departmentsStatus = departments.isPending
      ? <p role="status" className="font-pixel text-[10px] text-[#8b5327] py-2">학과 목록을 불러오는 중…</p>
      : departments.isError
        ? (
          <div role="alert" className="font-pixel text-[10px] text-[#b91c1c] flex items-center gap-1.5 py-1">
            <span>학과 목록을 불러오지 못했습니다. {errorMessage(departments.error)}</span>
            <button type="button" onClick={() => void departments.refetch()} className="min-h-6 shrink-0 underline cursor-pointer">다시 시도</button>
          </div>
        )
        : departments.data.length === 0
          ? <p role="status" className="font-pixel text-[10px] text-[#8b5327] py-2">등록된 학과가 없습니다.</p>
          : undefined;
    content = (
      <ProfileStep
        school={schoolBadge(stage.university)}
        email={stage.email}
        nickname={nickname}
        onNicknameChange={(value) => {
          setNickname(value);
          setNicknameStatus(null);
          setError(null);
        }}
        nicknameMaxLength={NICKNAME_RULE.max}
        onCheckNickname={() => {
          const problem = nicknameProblem(nickname);
          if (problem) {
            setNicknameStatus({ ok: false, message: problem });
            return;
          }
          const checked = nickname;
          nicknameCheck.mutate(checked, {
            onSuccess: ({ available }) => setNicknameStatus({ ok: available, message: available ? NICKNAME_AVAILABLE : NICKNAME_TAKEN }),
            onError: (failure) => setNicknameStatus({ ok: false, message: fieldErrorMap(failure).nickname ?? errorMessage(failure) }),
          });
        }}
        nicknameStatus={nicknameStatus}
        departments={(departments.data ?? []).map((department) => ({ id: String(department.id), name: department.name }))}
        departmentsStatus={departmentsStatus}
        departmentId={departmentId}
        onDepartmentChange={(value) => { setDepartmentId(value); setError(null); }}
        error={error}
        pending={pending}
        onEnroll={() => {
          if (Date.now() >= stage.tokenExpiresAt) { restart('가입 가능 시간이 지났어요. 이메일 인증을 다시 해 주세요.'); return; }
          const problem = nicknameProblem(nickname) ?? (departmentId ? null : '학과를 선택해 주세요.');
          if (problem) {
            show(problem);
            return;
          }
          enroll.mutate({ signupToken: stage.signupToken, nickname, password: stage.password, departmentId: Number(departmentId) }, {
            onSuccess: (result) => {
              // The account exists now; a failed login only leaves the user to log in on the login screen.
              signIn.mutate({ email: stage.email, password: stage.password }, {
                onSettled: (_, loginError) => {
                  setStage({ step: 'done', result, signedIn: !loginError });
                  setPassword('');
                  setConfirm('');
                  show(null);
                },
              });
            },
            onError: (failure) => {
              const errorCode = codeOf(failure);
              if (errorCode === SIGNUP_ERROR_CODES.invalidSignupToken || errorCode === SIGNUP_ERROR_CODES.emailAlreadyRegistered) {
                restart(errorMessage(failure));
                return;
              }
              if (errorCode === SIGNUP_ERROR_CODES.nicknameDuplicated) setNicknameStatus({ ok: false, message: NICKNAME_TAKEN });
              const fields = fieldErrorMap(failure);
              if (fields.password) {
                setStage({ step: 'password', email: stage.email, university: stage.university, signupToken: stage.signupToken, tokenExpiresAt: stage.tokenExpiresAt });
                show(fields.password);
                return;
              }
              show(fields.nickname ?? fields.departmentId ?? errorMessage(failure));
            },
          });
        }}
      />
    );
  } else {
    const { result } = stage;
    content = (
      <DoneStep
        school={schoolBadge(result.university)}
        nickname={result.nickname}
        department={result.department.name}
        startedOver={null}
        notice={stage.signedIn ? null : '가입은 끝났어요. 로그인하지 못했으니 로그인 화면에서 다시 로그인해 주세요.'}
        onGoToCampus={onGoToCampus}
        onRestart={() => {
          setEmailInput('');
          restart(null);
        }}
      />
    );
  }

  const seconds = tokenExpiresAt === null ? 0 : Math.ceil((tokenExpiresAt - now) / 1000);
  if (seconds > 0 && seconds <= 300) {
    content = <>{content}<p className="font-pixel text-[10px] text-[#8b5327]">가입 가능 시간이 {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')} 남았어요. 시간이 지나면 이메일 인증부터 다시 해야 해요.</p></>;
  }
  if (fullShell) return <PhoneFrame label="회원가입">{content}</PhoneFrame>;
  return (
    <div className="w-full flex flex-col items-center py-2">
      <p className="mb-2 font-pixel text-xs text-[#8b5327] font-bold">회원가입 · 서버 연동 (학교 메일로 코드 발송)</p>
      <PhoneFrame label="회원가입">{content}</PhoneFrame>
    </div>
  );
};
