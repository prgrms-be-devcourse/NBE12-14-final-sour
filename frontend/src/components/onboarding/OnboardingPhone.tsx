import type { SchoolId } from '../../data/schoolIds.ts';
import React, { useContext, useState } from 'react';
import { PhoneShellVariantContext } from '../PhoneShell.tsx';
import { useMockAuth } from '../../hooks/useMockAuth.tsx';
import { useMockGame } from '../../hooks/useMockGame.tsx';
import { useMockRequest } from '../../hooks/useMockRequest.tsx';
import { REQUEST_FAILED_MESSAGE } from '../../utils/mockRequest.ts';
import { getSchoolDepartments } from '../../data/gameRules.ts';
import { REAL_UNIVERSITIES } from '../../data/universitiesData.ts';
import { attemptsLeft, cooldownSeconds, describeAuthError, remainingSeconds, type AuthResult } from '../../utils/mockAuth.ts';
import { CodeStep, DoneStep, EmailStep, PhoneFrame, ProfileStep, type OnboardingSchool, type ProgressChoice } from './OnboardingSteps.tsx';

const AUTH_REQUEST = 'auth';

function schoolInfo(schoolId: SchoolId | null): OnboardingSchool {
  const school = REAL_UNIVERSITIES.find((entry) => entry.id === schoolId);
  if (!school) throw new Error(`Unknown school: ${schoolId}`);
  return { name: school.name, firstSyllable: school.firstSyllable, badgeColor: school.badgeColor };
}

interface OnboardingPhoneProps {
  onGoToCampus: () => void;
}

/**
 * Sequential onboarding on one phone: email → code → profile → campus (M4-B).
 * The step comes from the shared auth state, so reopening this screen continues where it was.
 */
export const OnboardingPhone: React.FC<OnboardingPhoneProps> = ({ onGoToCampus }) => {
  const auth = useMockAuth();
  const game = useMockGame();
  const fullShell = useContext(PhoneShellVariantContext) === 'full';
  const { state, now } = auth;
  const [emailInput, setEmailInput] = useState(state.email);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [nickname, setNickname] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [progressChoice, setProgressChoice] = useState<ProgressChoice>('keep');
  const [startedOver, setStartedOver] = useState<boolean | null>(null);

  // Every auth step is a mock request (M5-B). The auth rule runs only when the request succeeds, so a failed
  // request uses no attempt and no code, and keeps what was typed. A second submit while pending is refused.
  const request = useMockRequest();
  const pending = request.isPending(AUTH_REQUEST);
  const send = (apply: () => void) => {
    request.run(AUTH_REQUEST, (ok) => {
      if (ok) {
        apply();
        return;
      }
      setError(REQUEST_FAILED_MESSAGE);
      setNotice(null);
    });
  };

  const report = (result: AuthResult, success?: string) => {
    setError(result.ok ? null : describeAuthError(result.error, result.state, Date.now()));
    setNotice(result.ok ? success ?? null : null);
    return result;
  };

  let content: React.ReactNode;
  if (state.step === 'email') {
    content = (
      <EmailStep
        email={emailInput}
        onEmailChange={(value) => {
          setEmailInput(value);
          setError(null);
        }}
        onRequestCode={() => {
          const email = emailInput;
          send(() => report(auth.requestCode(email)));
        }}
        error={error}
        pending={pending}
      />
    );
  } else if (state.step === 'code' && state.challenge) {
    content = (
      <CodeStep
        key={state.challenge.issuedAt}
        email={state.email}
        school={schoolInfo(state.schoolId)}
        remainingSeconds={remainingSeconds(state, now)}
        attemptsLeft={attemptsLeft(state)}
        resendWaitSeconds={cooldownSeconds(state, state.email, now)}
        mailboxCode={state.challenge.code}
        error={error}
        notice={notice}
        pending={pending}
        onVerify={(code) => send(() => {
          const result = report(auth.verify(code));
          if (result.ok) {
            const departments = getSchoolDepartments(result.state.schoolId!);
            setDepartmentId(departments[0]?.id ?? '');
            setNickname(result.state.schoolId === game.player.schoolId ? game.player.nickname : '');
          }
        })}
        onResend={() => send(() => report(auth.resend(), '새 목업 코드가 발급됐습니다. 이전 코드는 더 이상 쓸 수 없어요.'))}
        onEditEmail={() => {
          auth.editEmail();
          setError(null);
          setNotice(null);
        }}
        onExpireDemo={auth.expireForDemo}
      />
    );
  } else if (state.step === 'profile' && state.verification) {
    const sameSchool = state.verification.schoolId === game.player.schoolId;
    content = (
      <ProfileStep
        school={schoolInfo(state.verification.schoolId)}
        email={state.verification.email}
        nickname={nickname}
        onNicknameChange={setNickname}
        departments={getSchoolDepartments(state.verification.schoolId)}
        departmentId={departmentId}
        onDepartmentChange={setDepartmentId}
        progressChoice={sameSchool ? progressChoice : null}
        onProgressChoiceChange={setProgressChoice}
        error={error}
        pending={pending}
        onEnroll={() => {
          const input = { nickname, departmentId };
          const mode = sameSchool ? progressChoice : 'reset';
          send(() => {
            const result = report(auth.complete(input));
            // A finished profile is applied once; a repeated submit gets 'already-completed' and changes nothing.
            if (result.ok && result.state.completed) setStartedOver(game.enroll(result.state.completed, mode));
          });
        }}
      />
    );
  } else if (state.step === 'done' && state.completed) {
    content = (
      <DoneStep
        school={schoolInfo(state.completed.schoolId)}
        nickname={state.completed.nickname}
        department={state.completed.department}
        startedOver={startedOver}
        onGoToCampus={onGoToCampus}
        onRestart={() => {
          auth.restart();
          setEmailInput('');
          setError(null);
          setNotice(null);
          setStartedOver(null);
        }}
      />
    );
  }

  // On a phone (T2) the frame fills the viewport; the mock mailbox inside still says nothing is sent.
  if (fullShell) return <PhoneFrame label="순차 온보딩">{content}</PhoneFrame>;
  return (
    <div className="w-full flex flex-col items-center py-2">
      <p className="mb-2 font-pixel text-xs text-[#8b5327] font-bold">순차 온보딩 · 목업 인증 (실제 메일 발송 없음)</p>
      <PhoneFrame label="순차 온보딩">{content}</PhoneFrame>
    </div>
  );
};
