import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useAuthSession } from '../api/ApiProvider';
import { useSessionRestore } from '../api/auth';
import { errorMessage } from '../api/errors';
import campusArt from '../assets/campus-shots/lv07.webp';
import { PhoneShellVariantContext } from './PhoneShell';
import { PublicUniversityTop } from './PublicUniversityTop';
import { SupportGuestScreenPhone } from './SupportGuestScreenPhone';
import { ApiLoginPhone, ApiSessionWatcher } from './onboarding/ApiLoginPhone';
import { ApiSignupPhone } from './onboarding/ApiSignupPhone';

type GuestScreen = 'landing' | 'login' | 'signup' | 'support';

function GuestLanding({ notice, onNavigate }: {
  notice: string | null;
  onNavigate: (screen: GuestScreen) => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus({ preventScroll: true }); }, []);

  return (
    <main className="guest-entry game-backdrop">
      <div className="guest-landing">
        <header className="guest-hero rpg-box">
          <div className="guest-hero-copy">
            <p className="font-bold text-[#67431f]">전국 대학 대항전 · 픽셀 클리커</p>
            <h1 ref={heading} tabIndex={-1} className="text-4xl sm:text-5xl font-black">대대대</h1>
            <p>캠퍼스를 클릭해 우리 학교를 성장시키고 전국 대학과 겨뤄 보세요.</p>
            {notice && <p role="status" className="text-sm text-[#792e17]">{notice}</p>}
            <div className="guest-actions">
              <button type="button" className="pixel-plaque plaque-green" onClick={() => onNavigate('login')}>로그인</button>
              <button type="button" className="pixel-plaque plaque-yellow" onClick={() => onNavigate('signup')}>회원가입</button>
            </div>
            <button type="button" className="guest-support" onClick={() => onNavigate('support')}>비회원 문의</button>
          </div>
          <img src={campusArt} alt="" width={716} height={734} className="guest-hero-art" />
        </header>
        <PublicUniversityTop />
      </div>
    </main>
  );
}

function GuestStep({ screen, onBack, children }: {
  screen: Exclude<GuestScreen, 'landing'>;
  onBack: () => void;
  children: ReactNode;
}) {
  const main = useRef<HTMLElement>(null);
  useEffect(() => { main.current?.focus(); }, [screen]);
  return (
    <main ref={main} tabIndex={-1} aria-label="비로그인 서비스" className="guest-entry game-backdrop" data-touch-area="44">
      <div className="guest-step">
        {screen !== 'support' && <button type="button" className="game-workspace-action" onClick={onBack}>첫 화면으로</button>}
        <PhoneShellVariantContext.Provider value="full">{children}</PhoneShellVariantContext.Provider>
      </div>
    </main>
  );
}

function GuestScreens({ notice, signingUp, onSignupChange }: {
  notice: string | null;
  signingUp: boolean;
  onSignupChange: (active: boolean) => void;
}) {
  const session = useAuthSession();
  const [route, setRoute] = useState<Exclude<GuestScreen, 'signup'>>('landing');
  const screen = signingUp ? 'signup' : route;
  const navigate = (next: GuestScreen) => {
    onSignupChange(next === 'signup');
    setRoute(next === 'signup' ? 'landing' : next);
  };
  const goHome = () => navigate('landing');
  if (screen === 'landing') return <GuestLanding notice={notice} onNavigate={navigate} />;

  let step: ReactNode;
  if (screen === 'login') {
    step = <ApiLoginPhone onGoToSignup={() => navigate('signup')} onGoToCampus={goHome} />;
  } else if (screen === 'signup') {
    step = <ApiSignupPhone onGoToLogin={() => navigate('login')}
      onGoToCampus={() => navigate(session.status === 'signed-in' ? 'landing' : 'login')} />;
  } else {
    step = <SupportGuestScreenPhone onBack={goHome} />;
  }
  return <GuestStep screen={screen} onBack={goHome}>{step}</GuestStep>;
}

/** Gate the entire private workspace, not just the wide layout. Keep signup's existing done step after auto-login. */
export function ApiEntry({ children }: { children: ReactNode }) {
  const session = useAuthSession();
  const restore = useSessionRestore();
  const [signingUp, setSigningUp] = useState(false);
  let content: ReactNode;
  if (session.status === 'signed-in' && !signingUp) {
    content = children;
  } else if (session.status === 'signed-out' && restore.isPending) {
    content = <main className="guest-entry guest-restore game-backdrop"><p role="status">로그인 상태를 확인하는 중…</p></main>;
  } else {
    const notice = (session.status === 'signed-out' && session.ended?.message)
      || (restore.isError ? `로그인 상태를 확인하지 못했습니다. ${errorMessage(restore.error)}` : null);
    content = <GuestScreens notice={notice} signingUp={signingUp} onSignupChange={setSigningUp} />;
  }
  return <><ApiSessionWatcher onEnded={() => setSigningUp(false)} />{content}</>;
}
