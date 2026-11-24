import React from 'react';
import { useMockLoad } from '../hooks/useMockRequest.tsx';
import { LoadNotice } from './LoadNotice';

interface MockLoadGateProps {
  /** What is being loaded, shown in the notice (e.g. "순위"). */
  name: string;
  /** 'phone' fills a phone frame (full screens); 'panel' sits inside the game screen (tabs). */
  variant?: 'phone' | 'panel';
  onBack?: () => void;
  backLabel?: string;
  children: React.ReactNode;
}

export const MOCK_LOAD_FAILURE_DETAIL = '[시연] 네트워크 오류 · 다시 시도하면 이어서 볼 수 있어요.';

/**
 * Loading and failure states for a service screen (M5-B). In normal mode it renders the screen directly.
 * Retrying is the only way forward from a failure besides going back; the screen's own state is not touched.
 */
export const MockLoadGate: React.FC<MockLoadGateProps> = ({ name, variant = 'phone', onBack, backLabel, children }) => {
  const { status, retry } = useMockLoad(name);
  if (status === 'ready') return <>{children}</>;
  return (
    <LoadNotice
      status={status}
      name={name}
      failureDetail={MOCK_LOAD_FAILURE_DETAIL}
      onRetry={retry}
      variant={variant}
      onBack={onBack}
      backLabel={backLabel}
    />
  );
};
