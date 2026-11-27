import React, { useRef, useState } from 'react';
import { Mail, Check, RefreshCw, Clock, ArrowRight, Sparkles, CheckCircle2, Inbox, Hourglass, KeyRound, LogIn, LogOut } from 'lucide-react';
import { CampusLayoutScene } from '../CampusScene';
import brandLogo from '../../assets/brand/daedaedae-logo-final.png';
import { PhoneShell } from '../PhoneShell';
import { TITLE_LAYOUT } from '../../data/campusLayouts';
import { soundManager } from '../../utils/sound';
import { extractCodeDigits } from '../../utils/codeDigits';
import { MOCK_SAMPLE_EMAILS } from '../../data/mockEmailDomains.ts';
import { INITIAL_CONTRIBUTION } from '../../data/gameRules.ts';
import { REAL_UNIVERSITIES } from '../../data/universitiesData.ts';

// Onboarding step screens (M4-B, docs/261108-2020-m4_onboarding_auth_plan.md 4-2). Each step only shows the
// values it gets and reports user actions; the sequential phone and the 3-screen comparison share them.

export interface OnboardingSchool {
  name: string;
  firstSyllable: string;
  badgeColor: string;
}

export const PhoneFrame: React.FC<{ children: React.ReactNode; label?: string }> = ({ children, label }) => (
  <PhoneShell label={label}>
    {/* select-text: the old onboarding frame had no select-none, so the mock mailbox code stays copyable (T1b). */}
    <div className="flex-1 min-h-0 flex flex-col justify-between p-3 overflow-y-auto bg-[#faecc7]/20 select-text">{children}</div>
  </PhoneShell>
);

const ErrorText: React.FC<{ message?: string | null }> = ({ message }) =>
  message ? (
    <p role="alert" className="font-pixel text-[10px] font-bold text-[#b91c1c] bg-[#fee2e2] border border-[#b91c1c] px-1.5 py-1 leading-tight">
      {message}
    </p>
  ) : null;

const SchoolBadge: React.FC<{ school: OnboardingSchool; size?: string }> = ({ school, size = 'w-8 h-8 text-sm' }) => (
  <span
    aria-hidden="true"
    className={`${size} shrink-0 border-2 border-[#261608] flex items-center justify-center font-pixel font-black text-white`}
    style={{ backgroundColor: school.badgeColor }}
  >
    {school.firstSyllable}
  </span>
);

/** Accessible name of the title scene; the logo text stays readable on its own. */
export const TITLE_SCENE_LABEL = '타이틀 그림: 시계탑 캠퍼스로 걸어 들어가는 새내기들';

/**
 * Square title art (docs/261110-0837-onboarding_title_screen_plan.md 4): the text logo in a tall sky over
 * the composed title scene, pinned to day so the logo always reads against a light sky.
 */
const TitleArt: React.FC = () => (
  <div data-testid="title-art" className="w-full aspect-square relative border-3 border-[#261608] shadow-[3px_3px_0px_#261608] overflow-hidden bg-[#5db6ee] shrink-0">
    <CampusLayoutScene layout={TITLE_LAYOUT} phase="day" testId="title-scene" label={TITLE_SCENE_LABEL} className="absolute inset-0 block w-full h-full" />
    <div className="absolute inset-x-0 top-0 pt-[5%] flex flex-col items-center z-10 pointer-events-none">
      <div className="animate-logo-bounce flex flex-col items-center">
        <p
          className="font-pixel text-4xl sm:text-5xl font-black text-[#facc15] tracking-wider select-none leading-none"
          style={{ textShadow: '3px 3px 0px #261608, -2px -2px 0px #261608, 2px -2px 0px #261608, -2px 2px 0px #261608, 0px 4px 0px #e11d48' }}
        >
          대대대
        </p>
        <span className="font-pixel text-[11px] font-bold text-[#ffffff] bg-[#e11d48] border-2 border-[#261608] px-2 py-0.5 mt-1 shadow-[2px_2px_0px_#261608]">
          전국 대학 대항전 픽셀 클리커
        </span>
      </div>
    </div>
  </div>
);

/**
 * Strip under the title art (plan 5, F1): the schools of the mock league as badges flowing in one loop.
 * Decorative, so the badges are hidden from screen readers; the caption says what they are.
 */
const SchoolBelt: React.FC = () => (
  <div data-testid="school-belt" className="mt-2 flex flex-col items-center gap-1 shrink-0">
    <p className="font-pixel text-[10px] font-bold text-[#8b5327]">전국 {REAL_UNIVERSITIES.length}개 대학 대항전</p>
    <div aria-hidden="true" className="w-full overflow-hidden border-y-2 border-[#261608] bg-[#faecc7] py-1">
      <div className="school-belt-track flex w-max">
        {[...REAL_UNIVERSITIES, ...REAL_UNIVERSITIES].map((school, i) => (
          <SchoolBadge key={i} school={school} size="w-6 h-6 text-[11px] mr-1.5" />
        ))}
      </div>
    </div>
  </div>
);

const formatClock = (seconds: number) =>
  `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`;

// ---------------------------------------------------------------------------------------------------------
// Step 1. Email
// ---------------------------------------------------------------------------------------------------------

interface EmailStepProps {
  email: string;
  onEmailChange: (email: string) => void;
  onRequestCode: () => void;
  error?: string | null;
  /** A mock request is in flight (M5-B): the submit button waits. */
  pending?: boolean;
  /** Quick-fill buttons; the API sign-up passes none, since the server decides which domains it knows. */
  sampleEmails?: readonly string[];
  /** Shows a link to the login screen for members who already signed up (API mode). */
  onGoToLogin?: () => void;
}

const PENDING_LABEL = '요청 중…';

export const EmailStep: React.FC<EmailStepProps> = ({ email, onEmailChange, onRequestCode, error, pending = false, sampleEmails = MOCK_SAMPLE_EMAILS, onGoToLogin }) => (
  <>
    <TitleArt />
    <SchoolBelt />
    <form
      className="rpg-box p-3 mt-3 flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        soundManager.playBuySound();
        onRequestCode();
      }}
    >
      <div className="flex items-center gap-1.5 border-b-2 border-[#261608]/20 pb-1.5">
        <Mail className="w-4 h-4 text-[#8b5327]" />
        <h2 className="font-pixel text-xs sm:text-sm font-bold text-[#261608]">학교 이메일로 참여하기</h2>
      </div>
      <p className="font-pixel text-[10px] text-[#8b5327] leading-tight">
        이메일 도메인으로 소속 학교가 정해집니다. 학교를 따로 고르지 않아요.
      </p>
      <label className="font-pixel text-[10px] font-bold text-[#261608] flex flex-col gap-1">
        학교 이메일 주소
        <input
          type="email"
          value={email}
          onChange={(e) => onEmailChange(e.target.value)}
          placeholder="student@hanyang.ac.kr"
          className="w-full bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 text-xs font-mono text-[#261608] placeholder:text-stone-400 focus:outline-none focus:bg-[#fef9c3] shadow-[inset_1px_1px_0px_#261608]"
        />
      </label>
      {sampleEmails.length > 0 && <div className="flex items-center gap-x-1 gap-y-5 flex-wrap py-2.5" aria-label="목업 샘플 이메일">
        {sampleEmails.map((sample) => (
          <button
            key={sample}
            type="button"
            onClick={() => {
              onEmailChange(sample);
              soundManager.playTabSound();
            }}
            className="min-h-6 min-w-6 text-[9px] font-mono bg-[#faecc7] hover:bg-[#fef08a] text-[#8b5327] border border-[#261608] px-1 py-0.5 cursor-pointer"
          >
            @{sample.split('@')[1]}
          </button>
        ))}
      </div>}
      <ErrorText message={error} />
      <button
        type="submit"
        disabled={pending}
        className="pixel-plaque plaque-yellow w-full py-2 mt-1 text-xs font-pixel font-bold flex items-center justify-center gap-1.5 cursor-pointer disabled:opacity-60 disabled:cursor-wait"
      >
        <span>{pending ? PENDING_LABEL : '인증 코드 받기'}</span>
        <ArrowRight className="w-3.5 h-3.5" />
      </button>
      {onGoToLogin && (
        <button type="button" onClick={onGoToLogin} className="min-h-6 self-center font-pixel text-[10px] text-[#8b5327] underline cursor-pointer">
          이미 가입했어요 · 로그인
        </button>
      )}
    </form>
  </>
);

// ---------------------------------------------------------------------------------------------------------
// Step 2. Code
// ---------------------------------------------------------------------------------------------------------

interface CodeStepProps {
  email: string;
  school: OnboardingSchool;
  remainingSeconds: number;
  attemptsLeft: number;
  resendWaitSeconds: number;
  /**
   * Code shown in the mock mailbox; there is no real mail in the mockup. Left out (undefined) when a real mail is
   * sent: the mock mailbox is not shown.
   */
  mailboxCode?: string | null;
  initialDigits?: readonly string[];
  error?: string | null;
  notice?: string | null;
  onVerify: (code: string) => void;
  onResend: () => void;
  onEditEmail: () => void;
  onExpireDemo?: () => void;
  pending?: boolean;
}

export const CodeStep: React.FC<CodeStepProps> = ({
  email, school, remainingSeconds, attemptsLeft, resendWaitSeconds, mailboxCode, initialDigits,
  error, notice, onVerify, onResend, onEditEmail, onExpireDemo, pending = false,
}) => {
  const [digits, setDigits] = useState<string[]>(() => Array.from({ length: 6 }, (_, i) => initialDigits?.[i] ?? ''));
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);
  const submitRef = useRef<HTMLButtonElement>(null);

  const distribute = (index: number, raw: string) => {
    const value = extractCodeDigits(raw);
    if (!value) return;
    const start = value.length === 6 ? 0 : index;
    const next = digits.map((digit, i) => i >= start && i < start + value.length ? value[i - start] : digit);
    setDigits(next);
    const empty = next.findIndex((digit) => !digit);
    if (empty < 0) submitRef.current?.focus();
    else inputRefs.current[empty]?.focus();
  };

  const change = (index: number, raw: string) => {
    if (raw.replace(/\D/g, '').length > 1) { distribute(index, raw); return; }
    const value = raw.replace(/\D/g, '').slice(-1);
    setDigits((prev) => prev.map((digit, i) => (i === index ? value : digit)));
    if (value && index < 5) inputRefs.current[index + 1]?.focus();
  };

  return (
    // One block centered in the frame: PhoneFrame spreads its children apart (M4 #5 = M6-D2).
    <div data-testid="code-step" className="my-auto flex flex-col gap-3">
      <div className="rpg-box-sky p-3 flex items-center gap-3 relative">
        <SchoolBadge school={school} size="w-12 h-12 text-2xl" />
        <div className="min-w-0">
          <span className="text-[9px] font-pixel bg-[#261608] text-[#fef08a] px-1 py-0.2">{school.name} 확인 중</span>
          <h2 className="font-pixel text-xs font-bold text-white mt-0.5">{mailboxCode === undefined ? '인증 코드를 메일로 보냈어!' : '목업 인증 코드가 발급됐어!'}</h2>
          <p className="font-mono text-[10px] text-white/90 truncate">{email}</p>
        </div>
      </div>

      {mailboxCode === undefined ? (
        <p className="border-2 border-dashed border-[#261608]/60 bg-[#ffffff] p-2 flex items-center gap-2 font-pixel text-[10px] text-[#5a3012] leading-tight">
          <Inbox className="w-4 h-4 text-[#8b5327] shrink-0" />
          학교 메일함에서 6자리 코드를 확인해 줘. 메일이 없으면 스팸함도 살펴봐!
        </p>
      ) : (
        <section aria-label="목업 메일함" className="border-2 border-dashed border-[#261608]/60 bg-[#ffffff] p-2 flex items-center gap-2">
          <Inbox className="w-4 h-4 text-[#8b5327] shrink-0" />
          <div className="font-pixel text-[10px] text-[#5a3012] leading-tight">
            <div className="font-bold">목업 메일함 (실제 발송 없음)</div>
            <div>
              인증 코드 <span data-testid="mock-mailbox-code" className="font-press-start text-[11px] text-[#261608]">{mailboxCode ?? '------'}</span>
            </div>
          </div>
        </section>
      )}

      <form
        className="rpg-box p-3 flex flex-col gap-2.5"
        onSubmit={(event) => {
          event.preventDefault();
          soundManager.playBuySound();
          onVerify(digits.join(''));
        }}
      >
        <h3 className="font-pixel text-xs sm:text-sm font-bold text-[#261608] leading-tight text-center">6자리 코드를 입력해줘!</h3>
        <div className="flex items-center justify-center gap-1.5 py-1">
          {digits.map((digit, idx) => (
            <input
              key={idx}
              ref={(el) => { inputRefs.current[idx] = el; }}
              aria-label={`인증 코드 ${idx + 1}번째 자리`}
              inputMode="numeric"
              autoComplete={idx === 0 ? 'one-time-code' : 'off'}
              type="text"
              maxLength={idx === 0 ? 6 : 1}
              value={digit}
              onChange={(e) => change(idx, e.target.value)}
              onPaste={(event) => { event.preventDefault(); distribute(idx, event.clipboardData.getData('text')); }}
              onKeyDown={(e) => {
                if (e.key === 'Backspace' && !digits[idx] && idx > 0) inputRefs.current[idx - 1]?.focus();
              }}
              className="w-10 h-12 bg-[#ffffff] border-3 border-[#261608] text-center font-press-start text-base text-[#261608] focus:bg-[#fef08a] focus:outline-none shadow-[2px_2px_0px_#261608]"
            />
          ))}
        </div>
        <div className="flex items-center justify-center gap-1.5 text-[11px] font-mono font-bold">
          <span className="pixel-plaque plaque-coral px-2.5 py-1 flex items-center gap-1">
            <Clock className="w-3.5 h-3.5" />
            <span data-testid="code-remaining">남은 시간 {formatClock(remainingSeconds)}</span>
          </span>
          <span data-testid="attempts-left" className="font-pixel text-[10px] text-[#8b5327]">남은 시도 {attemptsLeft}회</span>
        </div>
        {notice && (
          <p role="status" className="text-center font-pixel text-[10px] text-[#15803d] font-bold bg-[#dcfce7] border border-[#22c55e] py-1">
            {notice}
          </p>
        )}
        <ErrorText message={error} />
        <div className="flex items-center justify-around border-t-2 border-[#261608]/20 pt-2 text-[11px] font-pixel font-bold">
          <button type="button" onClick={onResend} disabled={pending} className="min-h-6 px-1 disabled:opacity-60 disabled:cursor-wait flex items-center gap-1 text-[#8b5327] hover:text-[#261608] underline cursor-pointer">
            <RefreshCw className="w-3 h-3" />
            <span>{resendWaitSeconds > 0 ? `코드 다시 받기 (${resendWaitSeconds}초)` : '코드 다시 받기'}</span>
          </button>
          <button type="button" onClick={onEditEmail} className="min-h-6 px-1 text-stone-500 hover:text-[#261608] cursor-pointer">
            이메일 수정
          </button>
        </div>
        <button ref={submitRef} type="submit" disabled={pending} className="pixel-plaque plaque-yellow w-full py-2 text-xs font-pixel font-bold flex items-center justify-center gap-1 cursor-pointer disabled:opacity-60 disabled:cursor-wait">
          <span>{pending ? PENDING_LABEL : '코드 확인'}</span>
          <Check className="w-4 h-4" />
        </button>
        {onExpireDemo && (
          <button type="button" onClick={onExpireDemo} className="self-center font-pixel text-[9px] text-[#8b5327] border border-dashed border-[#8b5327] px-1.5 py-0.5 flex items-center gap-1 cursor-pointer">
            <Hourglass className="w-3 h-3" />
            [시연] 코드 만료시키기
          </button>
        )}
      </form>
    </div>
  );
};

// ---------------------------------------------------------------------------------------------------------
// Step 2-1. Password (API sign-up only, SCRUM-151)
// ---------------------------------------------------------------------------------------------------------

interface PasswordStepProps {
  school: OnboardingSchool;
  email: string;
  password: string;
  confirm: string;
  onPasswordChange: (password: string) => void;
  onConfirmChange: (confirm: string) => void;
  /** Shown under the fields, e.g. the server's rule. */
  ruleText: string;
  error?: string | null;
  onNext: () => void;
}

export const PasswordStep: React.FC<PasswordStepProps> = ({
  school, email, password, confirm, onPasswordChange, onConfirmChange, ruleText, error, onNext,
}) => {
  const inputClass = 'w-full bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 text-xs font-mono text-[#261608] focus:bg-[#fef9c3] focus:outline-none shadow-[inset_1px_1px_0px_#261608]';
  return (
    <div data-testid="password-step" className="my-auto flex flex-col gap-3">
      <div className="rpg-box p-2.5 flex items-center gap-2 bg-[#fef08a]">
        <SchoolBadge school={school} />
        <div className="min-w-0">
          <p className="font-pixel text-xs sm:text-sm font-bold text-[#261608]">{school.name} ✔</p>
          <p className="font-mono text-[9px] text-[#15803d] font-bold truncate">{email} 인증 완료</p>
        </div>
      </div>
      <form
        className="rpg-box p-3 flex flex-col gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          soundManager.playTabSound();
          onNext();
        }}
      >
        <div className="flex items-center gap-1.5 border-b-2 border-[#261608]/20 pb-1.5">
          <KeyRound className="w-4 h-4 text-[#8b5327]" />
          <h2 className="font-pixel text-xs sm:text-sm font-bold text-[#261608]">로그인 비밀번호 정하기</h2>
        </div>
        {/* The email as a hidden username lets password managers save the pair. */}
        <input type="email" name="username" autoComplete="username" value={email} readOnly hidden />
        <label className="font-pixel text-[10px] font-bold text-[#261608] flex flex-col gap-1">
          비밀번호
          <input type="password" autoComplete="new-password" value={password} onChange={(e) => onPasswordChange(e.target.value)} maxLength={64} className={inputClass} />
        </label>
        <label className="font-pixel text-[10px] font-bold text-[#261608] flex flex-col gap-1">
          비밀번호 확인
          <input type="password" autoComplete="new-password" value={confirm} onChange={(e) => onConfirmChange(e.target.value)} maxLength={64} className={inputClass} />
        </label>
        <p className="font-pixel text-[10px] text-[#8b5327] leading-tight">{ruleText}</p>
        <ErrorText message={error} />
        <button type="submit" className="pixel-plaque plaque-yellow w-full py-2 mt-1 text-xs font-pixel font-bold flex items-center justify-center gap-1.5 cursor-pointer">
          <span>다음</span>
          <ArrowRight className="w-3.5 h-3.5" />
        </button>
      </form>
    </div>
  );
};

// ---------------------------------------------------------------------------------------------------------
// Step 3. Profile
// ---------------------------------------------------------------------------------------------------------

export type ProgressChoice = 'keep' | 'reset';

interface ProfileStepProps {
  school: OnboardingSchool;
  email: string;
  nickname: string;
  onNicknameChange: (nickname: string) => void;
  departments: readonly { id: string; name: string }[];
  departmentId: string;
  onDepartmentChange: (departmentId: string) => void;
  /**
   * null: another school than the current player's, so the game always starts over (D2). Left out (undefined) for a
   * server account, which has no local progress to keep: the choice and the starting-contribution note are hidden.
   */
  progressChoice?: ProgressChoice | null;
  onProgressChoiceChange?: (choice: ProgressChoice) => void;
  error?: string | null;
  onEnroll: () => void;
  pending?: boolean;
  nicknameMaxLength?: number;
  /** Shows a 중복 확인 button next to the nickname. */
  onCheckNickname?: () => void;
  /** Result of the nickname check; `ok` picks the colour. */
  nicknameStatus?: { ok: boolean; message: string } | null;
  /** Shown in place of the department list while it loads or after it failed. */
  departmentsStatus?: React.ReactNode;
}

export const ProfileStep: React.FC<ProfileStepProps> = ({
  school, email, nickname, onNicknameChange, departments, departmentId, onDepartmentChange,
  progressChoice, onProgressChoiceChange, error, onEnroll, pending = false,
  nicknameMaxLength = 10, onCheckNickname, nicknameStatus, departmentsStatus,
}) => {
  const selected = departments.find((department) => department.id === departmentId);
  return (
    <>
      <div className="rpg-box p-2.5 flex items-center justify-between bg-[#fef08a]">
        <div className="flex items-center gap-2 min-w-0">
          <SchoolBadge school={school} />
          <div className="min-w-0">
            <p data-testid="verified-school" className="font-pixel text-xs sm:text-sm font-bold text-[#261608]">{school.name} ✔</p>
            <p className="font-mono text-[9px] text-[#15803d] font-bold truncate">{email} 인증 완료</p>
          </div>
        </div>
        <span className="font-pixel text-[10px] text-[#ffffff] bg-[#22c55e] border border-[#261608] px-1.5 py-0.5 shadow-[1px_1px_0px_#261608] shrink-0">인증완료</span>
      </div>

      <form
        className="rpg-box p-3 flex flex-col gap-2 my-1"
        onSubmit={(event) => {
          event.preventDefault();
          soundManager.playLevelUpSound();
          onEnroll();
        }}
      >
        <div className="border-b-2 border-[#261608]/20 pb-1">
          <h2 className="font-pixel text-xs sm:text-sm font-bold text-[#261608]">학생증 프로필 생성</h2>
          <p className="font-pixel text-[10px] text-[#8b5327]">순위와 채팅에 보일 닉네임과 학과를 정해줘!</p>
        </div>
        <div className="flex flex-col gap-1">
          <label className="font-pixel text-[10px] font-bold text-[#261608] flex flex-col gap-1">
            플레이어 닉네임
            <input
              type="text"
              value={nickname}
              onChange={(e) => onNicknameChange(e.target.value)}
              placeholder="열정학번사자"
              maxLength={nicknameMaxLength}
              className="w-full bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 text-xs font-pixel text-[#261608] focus:bg-[#fef9c3] focus:outline-none shadow-[inset_1px_1px_0px_#261608]"
            />
          </label>
          {onCheckNickname && (
            <div className="flex items-center gap-1.5">
              <button
                type="button"
                onClick={onCheckNickname}
                disabled={pending}
                className="min-h-6 shrink-0 font-pixel text-[10px] font-bold bg-[#faecc7] hover:bg-[#fef08a] text-[#261608] border border-[#261608] px-1.5 cursor-pointer disabled:opacity-60 disabled:cursor-wait"
              >
                중복 확인
              </button>
              {nicknameStatus && (
                <p role="status" data-nickname-ok={nicknameStatus.ok} className={`font-pixel text-[10px] font-bold leading-tight ${nicknameStatus.ok ? 'text-[#15803d]' : 'text-[#b91c1c]'}`}>
                  {nicknameStatus.message}
                </p>
              )}
            </div>
          )}
        </div>
        <div role="group" aria-label="소속 학과" className="flex flex-col gap-0.5">
          <span className="font-pixel text-[10px] font-bold text-[#261608]">소속 학과 · {school.name}</span>
          {departmentsStatus ?? <div className="border-2 border-[#261608] bg-[#ffffff] shadow-[3px_3px_0px_#261608] p-1 flex flex-col gap-0.5 max-h-[150px] overflow-y-auto">
            {departments.map((department) => {
              const isSelected = department.id === departmentId;
              return (
                <button
                  key={department.id}
                  type="button"
                  aria-pressed={isSelected}
                  onClick={() => {
                    onDepartmentChange(department.id);
                    soundManager.playTabSound();
                  }}
                  className={`flex items-center justify-between px-2 py-1 text-[11px] font-pixel text-left cursor-pointer ${
                    isSelected ? 'bg-[#fef08a] text-[#261608] font-bold border border-[#261608]' : 'hover:bg-[#faecc7] text-[#5a3012]'
                  }`}
                >
                  <span>{department.name}</span>
                  {isSelected && <CheckCircle2 className="w-3.5 h-3.5 text-[#22c55e]" />}
                </button>
              );
            })}
          </div>}
        </div>

        {progressChoice === undefined ? null : progressChoice === null ? (
          <p className="font-pixel text-[10px] text-[#9a3412] bg-[#ffedd5] border border-[#9a3412] px-1.5 py-1 leading-tight">
            지금 플레이 중인 학교와 달라요. {school.name}에서 처음부터 시작합니다(기존 진행은 초기화).
          </p>
        ) : (
          <fieldset className="font-pixel text-[10px] text-[#261608] border border-[#261608]/40 px-1.5 py-1 flex flex-col gap-0.5">
            <legend className="font-bold px-0.5">같은 학교로 다시 입학</legend>
            <label className="flex items-center gap-1 cursor-pointer">
              <input type="radio" name="progress" checked={progressChoice === 'keep'} onChange={() => onProgressChoiceChange?.('keep')} />
              진행 유지 (기여도·클리커·점수 그대로)
            </label>
            <label className="flex items-center gap-1 cursor-pointer">
              <input type="radio" name="progress" checked={progressChoice === 'reset'} onChange={() => onProgressChoiceChange?.('reset')} />
              처음부터 시작
            </label>
          </fieldset>
        )}

        <div className="border-2 border-dashed border-[#261608]/40 bg-[#faecc7]/50 p-2 flex items-center gap-1.5 text-[9px] font-pixel text-[#8b5327]">
          <span className="text-xl" aria-hidden="true">🪪</span>
          <div>
            <div className="font-bold text-[#261608]">{school.name} 학생증 발급 대기</div>
            <div>{nickname.trim() || '닉네임'} · {selected?.name ?? '학과'}</div>
          </div>
        </div>
        <ErrorText message={error} />
        <button
          type="submit"
          disabled={pending}
          className="pixel-plaque plaque-green w-full py-2.5 text-sm sm:text-base font-pixel font-black flex items-center justify-center gap-2 cursor-pointer shadow-[3px_3px_0px_#261608] disabled:opacity-60 disabled:cursor-wait"
        >
          <Sparkles className="w-4 h-4 text-yellow-200" />
          <span>{pending ? PENDING_LABEL : '입학하기!'}</span>
          <Sparkles className="w-4 h-4 text-yellow-200" />
        </button>
      </form>

      {progressChoice !== undefined && (
        <div className="rpg-box p-2 bg-[#dcfce7] text-center">
          <p className="font-pixel text-[10px] text-[#15803d] font-bold">
            처음부터 시작하면 기본 기여도 {INITIAL_CONTRIBUTION.toLocaleString('ko-KR')}점으로 출발해요!
          </p>
        </div>
      )}
    </>
  );
};

// ---------------------------------------------------------------------------------------------------------
// Done
// ---------------------------------------------------------------------------------------------------------

interface DoneStepProps {
  school: OnboardingSchool;
  nickname: string;
  department: string;
  startedOver: boolean | null;
  onGoToCampus: () => void;
  onRestart: () => void;
  /** A line under the welcome, e.g. that the automatic login after sign-up failed. */
  notice?: string | null;
}

export const DoneStep: React.FC<DoneStepProps> = ({ school, nickname, department, startedOver, onGoToCampus, onRestart, notice }) => (
  <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center">
    <div className="rpg-box p-5 bg-[#fef08a] w-full flex flex-col items-center gap-2">
      <span className="text-4xl" aria-hidden="true">🎉 🎓</span>
      <SchoolBadge school={school} size="w-12 h-12 text-2xl" />
      <h2 className="font-pixel text-lg font-bold text-[#261608]">{school.name} 입학을 환영합니다!</h2>
      <p className="font-pixel text-xs text-[#8b5327]">{nickname} ({department})</p>
      {startedOver !== null && (
        <p className="font-pixel text-[10px] text-[#261608]">{startedOver ? '새 기록으로 처음부터 시작합니다.' : '기존 진행을 그대로 이어 갑니다.'}</p>
      )}
      {notice && <p role="status" className="font-pixel text-[10px] font-bold text-[#9a3412]">{notice}</p>}
    </div>
    <button type="button" onClick={onGoToCampus} className="pixel-plaque plaque-green w-full py-2.5 text-sm font-pixel font-black cursor-pointer">
      캠퍼스로 이동
    </button>
    <button type="button" onClick={onRestart} className="font-pixel text-[10px] text-[#8b5327] underline cursor-pointer">
      온보딩 다시 시작
    </button>
  </div>
);

// ---------------------------------------------------------------------------------------------------------
// Login and the signed-in account (API mode, SCRUM-152)
// ---------------------------------------------------------------------------------------------------------

interface LoginStepProps {
  email: string;
  password: string;
  onEmailChange: (email: string) => void;
  onPasswordChange: (password: string) => void;
  onLogin: () => void;
  onGoToSignup: () => void;
  /** Why the last session ended (another device, suspension, expiry), shown above the form. */
  notice?: string | null;
  error?: string | null;
  pending?: boolean;
}

export const LoginStep: React.FC<LoginStepProps> = ({
  email, password, onEmailChange, onPasswordChange, onLogin, onGoToSignup, notice, error, pending = false,
}) => {
  const inputClass = 'w-full bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 text-xs font-mono text-[#261608] placeholder:text-stone-400 focus:outline-none focus:bg-[#fef9c3] shadow-[inset_1px_1px_0px_#261608]';
  return (
    <>
      <div className="flex flex-col items-center shrink-0">
        <img src={brandLogo} alt="대대대" width={1200} height={672} className="w-full max-w-[280px] h-auto" />
        <span className="font-pixel text-[11px] font-bold text-[#ffffff] bg-[#e11d48] border-2 border-[#261608] px-2 py-0.5 mt-1 shadow-[2px_2px_0px_#261608]">
          전국 대학 대항전 픽셀 클리커
        </span>
      </div>
      <form
        className="rpg-box p-3 mt-3 flex flex-col gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          soundManager.playBuySound();
          onLogin();
        }}
      >
        <div className="flex items-center gap-1.5 border-b-2 border-[#261608]/20 pb-1.5">
          <LogIn className="w-4 h-4 text-[#8b5327]" />
          <h2 className="font-pixel text-xs sm:text-sm font-bold text-[#261608]">로그인</h2>
        </div>
        {notice && (
          <p role="status" className="font-pixel text-[10px] font-bold text-[#9a3412] bg-[#ffedd5] border border-[#9a3412] px-1.5 py-1 leading-tight">{notice}</p>
        )}
        <label className="font-pixel text-[10px] font-bold text-[#261608] flex flex-col gap-1">
          학교 이메일 주소
          <input type="email" autoComplete="username" value={email} onChange={(e) => onEmailChange(e.target.value)} placeholder="student@hanyang.ac.kr" className={inputClass} />
        </label>
        <label className="font-pixel text-[10px] font-bold text-[#261608] flex flex-col gap-1">
          비밀번호
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => onPasswordChange(e.target.value)} maxLength={64} className={inputClass} />
        </label>
        <ErrorText message={error} />
        <button
          type="submit"
          disabled={pending}
          className="pixel-plaque plaque-yellow w-full py-2 mt-1 text-xs font-pixel font-bold flex items-center justify-center gap-1.5 cursor-pointer disabled:opacity-60 disabled:cursor-wait"
        >
          <span>{pending ? PENDING_LABEL : '로그인하기'}</span>
          <ArrowRight className="w-3.5 h-3.5" />
        </button>
        <button type="button" onClick={onGoToSignup} className="min-h-6 self-center font-pixel text-[10px] text-[#8b5327] underline cursor-pointer">
          처음이에요 · 학교 이메일로 가입하기
        </button>
      </form>
    </>
  );
};

interface SignedInStepProps {
  /** From GET /users/me (SCRUM-153), or the login response until the profile arrives. Null shows a plain heading. */
  nickname: string | null;
  onGoToCampus: () => void;
  onLogout: () => void;
  /** Shows the "내 정보" button (SCRUM-166). */
  onOpenMyInfo?: () => void;
  error?: string | null;
  pending?: boolean;
}

export const SignedInStep: React.FC<SignedInStepProps> = ({ nickname, onGoToCampus, onLogout, onOpenMyInfo, error, pending = false }) => (
  <div data-testid="signed-in" className="flex-1 flex flex-col items-center justify-center gap-3 text-center">
    <div className="rpg-box p-5 bg-[#dcfce7] w-full flex flex-col items-center gap-2">
      <CheckCircle2 className="w-10 h-10 text-[#15803d]" aria-hidden="true" />
      <h2 className="font-pixel text-lg font-bold text-[#261608]">{nickname ? `${nickname}님, 로그인되어 있어요` : '로그인되어 있어요'}</h2>
      <p className="font-pixel text-[10px] text-[#15803d]">새로 고쳐도 로그인이 유지됩니다.</p>
    </div>
    <ErrorText message={error} />
    <button type="button" onClick={onGoToCampus} className="pixel-plaque plaque-green w-full py-2.5 text-sm font-pixel font-black cursor-pointer">
      캠퍼스로 이동
    </button>
    {onOpenMyInfo && (
      <button type="button" onClick={onOpenMyInfo} className="pixel-plaque plaque-yellow w-full py-2 text-xs font-pixel font-bold cursor-pointer">
        내 정보
      </button>
    )}
    <button
      type="button"
      onClick={onLogout}
      disabled={pending}
      className="pixel-plaque plaque-parchment w-full py-2 text-xs font-pixel font-bold flex items-center justify-center gap-1.5 cursor-pointer disabled:opacity-60 disabled:cursor-wait"
    >
      <LogOut className="w-3.5 h-3.5" />
      <span>{pending ? PENDING_LABEL : '로그아웃'}</span>
    </button>
  </div>
);
