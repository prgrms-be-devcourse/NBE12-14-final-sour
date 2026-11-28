import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { AlertTriangle, Check, Store, X } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { BottomNav, NavTab } from './BottomNav';
import { type MockStoreItem } from '../data/gameRules';
import { useMockGame } from '../hooks/useMockGame';
import { useItemShop } from '../hooks/useItemShop';
import { ApiItemShop } from './ApiItemShop';
import { useMockRequest } from '../hooks/useMockRequest.tsx';
import { ImageChangeShop } from './ImageChangeShop.tsx';
import { PhoneShell } from './PhoneShell';

interface ShopScreenPhoneProps {
  embedded?: boolean;
  onConfirmationChange?: (confirming: boolean) => void;
  onNavigateTab: (tab: NavTab) => void;
}

/**
 * The standalone mock is a phone of its own; the shop tab sits inside the game screen's phone, which already
 * draws the status bar and home bar (T1b).
 */
function ShopFrame({ embedded, children }: { embedded: boolean; children: React.ReactNode }) {
  return embedded
    ? <div className="w-full bg-[#5c3414] flex flex-col relative">{children}</div>
    : <PhoneShell background="bg-[#5c3414]">{children}</PhoneShell>;
}

export function ShopScreenPhone(props: ShopScreenPhoneProps) {
  const shop = useItemShop();
  if (!shop) return <MockShopScreenPhone {...props} />;
  return (
    <ShopFrame embedded={props.embedded ?? false}>
      <header className="flex flex-wrap items-center justify-between gap-2 border-b-3 border-[#261608] bg-[#8b5327] px-3 py-2 font-pixel text-[#fef3c7]">
        <h1 className="flex items-center gap-1.5 text-sm font-black"><Store aria-hidden="true" className="size-4 shrink-0" />캠퍼스 잡화점</h1>
        <p className="border-2 border-[#261608] bg-[#fdf5df] px-2 py-1 text-xs font-bold text-[#261608]">보유 기여도 {shop.balance?.toLocaleString('ko-KR') ?? '—'}점</p>
      </header>
      <div className="dither-wood bg-[#6e3d16] p-2.5">
        <ApiItemShop shop={shop} onConfirmationChange={props.onConfirmationChange} />
      </div>
    </ShopFrame>
  );
}

const MockShopScreenPhone: React.FC<ShopScreenPhoneProps> = ({
  embedded = false,
  onConfirmationChange,
  onNavigateTab,
}) => {
  const game = useMockGame();
  const { level: clickerLevel, upgradeCost, power: clickerPower } = game.clicker;
  const contribution = game.state.contribution;
  const canUpgrade = upgradeCost !== null && contribution >= upgradeCost;

  // Selected card for purchase confirmation
  const [selectedItemId, setSelectedItemId] = useState<string>('coffee');
  const [showConfirmDialog, setShowConfirmDialog] = useState<boolean>(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const request = useMockRequest();
  const buying = request.isPending('shop-buy');
  const upgrading = request.isPending('shop-upgrade');
  const [requestError, setRequestError] = useState<{ key: 'shop-buy' | 'shop-upgrade'; message: string } | null>(null);

  const storeItems = game.storeItems;
  const selectedItem = storeItems.find((item) => item.id === selectedItemId) || storeItems[0];
  const confirmedItemId = showConfirmDialog ? selectedItem?.id : undefined;
  const confirmRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (confirmedItemId) confirmRef.current?.scrollIntoView({ block: 'nearest' });
  }, [confirmedItemId]);

  const [imageConfirming, setImageConfirming] = useState(false);
  const confirming = imageConfirming || (showConfirmDialog && !!selectedItem && !selectedItem.previewOnly);
  useLayoutEffect(() => {
    if (!confirming) return;
    onConfirmationChange?.(true);
    return () => onConfirmationChange?.(false);
  }, [confirming, onConfirmationChange]);

  const showToast = (msg: string) => setToastMessage(msg);
  useEffect(() => {
    if (!toastMessage) return;
    const timer = setTimeout(() => setToastMessage(null), 2400);
    return () => clearTimeout(timer);
  }, [toastMessage]);

  const handleSelectItem = (id: string) => {
    setSelectedItemId(id);
    setShowConfirmDialog(true);
    setRequestError((current) => (current?.key === 'shop-buy' ? null : current));
    soundManager.playTabSound();
  };

  const handleConfirmPurchase = () => {
    if (!selectedItem || selectedItem.previewOnly) return;
    const item = selectedItem;
    setRequestError(null);
    request.run('shop-buy', (ok) => {
      if (!ok) {
        // The dialog stays open on the same item, so pressing the button again is the retry.
        setRequestError({ key: 'shop-buy', message: '[시연] 구매 요청이 실패했습니다. 기여도는 그대로예요. 다시 시도해 주세요.' });
        return;
      }
      if (!game.buy(item.id)) {
        showToast('기여도가 부족합니다.');
        return;
      }
      soundManager.playBuySound();
      setShowConfirmDialog(false);
      showToast(`'${item.name}' 적용! 기존 버프를 교체했어요.`);
    });
  };

  const handleCancelPurchase = () => {
    soundManager.playTabSound();
    setShowConfirmDialog(false);
    setRequestError(null);
  };

  const handleUpgradeClicker = () => {
    setRequestError(null);
    request.run('shop-upgrade', (ok) => {
      if (!ok) {
        setRequestError({ key: 'shop-upgrade', message: '[시연] 강화 요청이 실패했습니다. 기여도는 그대로예요. 다시 시도해 주세요.' });
        return;
      }
      if (!game.upgrade()) {
        showToast(game.nextClicker ? '강화에 필요한 기여도가 부족합니다.' : '최고 단계입니다.');
        return;
      }
      soundManager.playLevelUpSound();
      showToast('클리커 강화 완료!');
    });
  };

  // Render 16-bit Pixel Icons
  const renderPixelIcon = (type: MockStoreItem['iconType']) => {
    switch (type) {
      case 'coffee':
        return (
          <div className="w-10 h-10 bg-[#fef08a] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center relative shrink-0">
            {/* 16-bit Pixel Coffee Mug with Steam */}
            <svg
              viewBox="0 0 16 16"
              className="w-7 h-7"
              style={{ shapeRendering: 'crispEdges' }}
            >
              {/* Steam */}
              <rect x="5" y="1" width="1" height="2" fill="#ca8a04" />
              <rect x="6" y="2" width="1" height="2" fill="#eab308" />
              <rect x="9" y="1" width="1" height="2" fill="#ca8a04" />
              <rect x="10" y="2" width="1" height="2" fill="#eab308" />
              {/* Cup Rim */}
              <rect x="3" y="5" width="8" height="1" fill="#261608" />
              {/* Cup Body */}
              <rect x="3" y="6" width="8" height="7" fill="#b45309" />
              <rect x="4" y="7" width="6" height="5" fill="#f59e0b" />
              <rect x="5" y="8" width="4" height="3" fill="#451a03" />
              {/* Cup Bottom */}
              <rect x="4" y="13" width="6" height="1" fill="#261608" />
              {/* Handle */}
              <rect x="11" y="7" width="2" height="4" fill="#261608" />
              <rect x="11" y="8" width="1" height="2" fill="#fef08a" />
            </svg>
          </div>
        );
      case 'flag':
        return (
          <div className="w-10 h-10 bg-[#ffe4e6] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center relative shrink-0">
            {/* 16-bit School Cheer Pennant */}
            <svg
              viewBox="0 0 16 16"
              className="w-7 h-7"
              style={{ shapeRendering: 'crispEdges' }}
            >
              {/* Flagpole */}
              <rect x="2" y="1" width="2" height="14" fill="#261608" />
              <rect x="2" y="1" width="1" height="13" fill="#ca8a04" />
              {/* Pole Top Ball */}
              <rect x="1" y="0" width="4" height="2" fill="#facc15" />
              {/* Pennant Flag */}
              <rect x="4" y="2" width="10" height="7" fill="#e11d48" />
              <rect x="5" y="3" width="8" height="5" fill="#fb7185" />
              {/* Star on Flag */}
              <rect x="8" y="4" width="2" height="3" fill="#fef08a" />
              <rect x="7" y="5" width="4" height="1" fill="#fef08a" />
              {/* Flag notch */}
              <rect x="13" y="4" width="2" height="3" fill="#ffe4e6" />
            </svg>
          </div>
        );
      case 'shard':
        return (
          <div className="w-10 h-10 bg-[#fef9c3] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center relative shrink-0">
            {/* 16-bit Golden Clicker Shard */}
            <svg
              viewBox="0 0 16 16"
              className="w-7 h-7"
              style={{ shapeRendering: 'crispEdges' }}
            >
              {/* Diamond Shard Shape */}
              <rect x="7" y="1" width="2" height="2" fill="#261608" />
              <rect x="5" y="3" width="6" height="2" fill="#261608" />
              <rect x="3" y="5" width="10" height="4" fill="#261608" />
              <rect x="5" y="9" width="6" height="3" fill="#261608" />
              <rect x="7" y="12" width="2" height="2" fill="#261608" />
              {/* Golden Core */}
              <rect x="6" y="3" width="4" height="2" fill="#facc15" />
              <rect x="4" y="5" width="8" height="4" fill="#fef08a" />
              <rect x="6" y="9" width="4" height="3" fill="#eab308" />
              {/* Shimmer Highlight */}
              <rect x="5" y="5" width="2" height="2" fill="#ffffff" />
            </svg>
          </div>
        );
      case 'pass':
        return (
          <div className="w-10 h-10 bg-[#e0f2fe] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center relative shrink-0">
            {/* 16-bit Library Book Pass */}
            <svg
              viewBox="0 0 16 16"
              className="w-7 h-7"
              style={{ shapeRendering: 'crispEdges' }}
            >
              {/* Ticket / Book Outline */}
              <rect x="2" y="3" width="12" height="10" fill="#261608" />
              <rect x="3" y="4" width="10" height="8" fill="#fdf5df" />
              {/* Spine */}
              <rect x="3" y="4" width="3" height="8" fill="#0284c7" />
              {/* Ribbon Bookmark */}
              <rect x="7" y="2" width="2" height="5" fill="#e11d48" />
              {/* Text lines */}
              <rect x="7" y="7" width="5" height="1" fill="#8b5327" />
              <rect x="7" y="9" width="4" height="1" fill="#8b5327" />
            </svg>
          </div>
        );
    }
  };

  return (
    <ShopFrame embedded={embedded}>
      {/* ========================================================= */}
      {/* 2. GENERAL STORE COUNTER CANOPY & HEADER                  */}
      {/* ========================================================= */}
      <div className="w-full bg-[#8b5327] border-b-3 border-[#261608] px-3 py-1.5 flex items-center justify-between shrink-0 shadow-[0_2px_0px_#381e09] z-30">
        <div className="flex items-center gap-2">
          {/* Fictional Store Logo / Awning Emblem */}
          <div className="w-6 h-6 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center shadow-[1px_1px_0px_#261608]">
            <span className="font-press-start text-[9px] text-[#261608] font-black">
              점
            </span>
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <span className="font-pixel text-xs font-black text-[#fef3c7] drop-shadow-[1px_1px_0px_#261608]">
                캠퍼스 잡화점 & 강화소
              </span>
              <span className="text-[9px] font-pixel text-[#261608] bg-[#facc15] border border-[#261608] px-1 py-0.2 font-bold shadow-[1px_1px_0px_#261608]">
                Lv.{clickerLevel}
              </span>
            </div>
            <p className="font-pixel text-[9px] text-[#fde68a] leading-none mt-0.5">
              아늑한 원목 선반과 클리커 강화대
            </p>
          </div>
        </div>

        {/* 공유 개인 기여도 표시 */}
        <div className="flex items-center gap-1 bg-[#fdf5df] border-2 border-[#261608] px-2 py-0.5 shadow-[2px_2px_0px_#261608]">
          <span className="w-2 h-2 bg-[#facc15] border border-[#261608] inline-block" />
          <span className="font-press-start text-[9px] text-[#261608] font-bold">
            {contribution}
          </span>
          <span className="font-pixel text-[9px] text-[#8b5327] font-bold">
            기여도
          </span>
        </div>
      </div>

      {/* Toast Notification Popup */}
      {toastMessage && (
        <div className="absolute top-14 left-1/2 -translate-x-1/2 z-50 bg-[#261608] text-[#fef08a] border-2 border-[#facc15] px-3 py-1 font-pixel text-[11px] shadow-[3px_3px_0px_rgba(0,0,0,0.5)] animate-bounce text-center whitespace-nowrap">
          {toastMessage}
        </div>
      )}

      {/* ========================================================= */}
      {/* 3. MAIN STORE AREA: WOODEN SHELVES BACKGROUND             */}
      {/* ========================================================= */}
      <div className="flex-1 flex flex-col justify-between overflow-y-auto overflow-x-hidden p-2.5 bg-[#6e3d16] relative dither-wood">
        {/* Subtle wooden plank lines in background */}
        <div className="absolute inset-0 pointer-events-none opacity-20 bg-[linear-gradient(to_bottom,transparent_31px,#261608_32px)] bg-[size:100%_32px]" />

        <div className="flex flex-col gap-2 relative z-10">
          {/* ========================================================= */}
          {/* TOP PANEL: MY CLICKER DISPLAYED LARGE ON A PEDESTAL       */}
          {/* 현재 클리커, 기본 세기, 강화 비용 대비 보유 기여도 */}
          {/* ========================================================= */}
          <div className="rpg-box p-2.5 flex flex-col gap-2 relative bg-[#fdf5df]">
            {/* Pedestal Header Tag */}
            <div className="flex items-center justify-between border-b-2 border-[#eeddb6] pb-1.5">
              <div className="flex items-center gap-1.5">
                <span className="w-2.5 h-2.5 bg-[#facc15] border border-[#261608]" />
                <span className="font-pixel text-xs font-black text-[#261608]">
                  내 클리커 강화 제단
                </span>
              </div>
              <span className="font-pixel text-[10px] text-[#8b5327] font-bold">
                {game.nextClicker ? `다음 목표: Lv.${game.nextClicker.level}` : '최고 단계 달성'}
              </span>
            </div>

            {/* Pedestal & Large Tool Display */}
            <div className="flex items-center gap-3 pt-0.5">
              {/* Stone & Wood Pedestal with Glowing Spot */}
              <div className="w-22 h-22 bg-[#ebd7a3] border-3 border-[#261608] shadow-[3px_3px_0px_#261608] flex flex-col items-center justify-center relative shrink-0 overflow-hidden">
                {/* Spotlight circle on pedestal */}
                <div className="absolute w-16 h-16 rounded-full bg-[#fef08a] border-2 border-[#ca8a04]/40" />

                {/* Pedestal Base Stone */}
                <div className="absolute bottom-0 inset-x-0 h-4 bg-[#b45309] border-t-2 border-[#261608] flex items-center justify-center">
                  <span className="font-press-start text-[6px] text-[#fde68a]">
                    PEDESTAL
                  </span>
                </div>

                <div className="relative z-10 flex flex-col items-center">
                  <svg
                    viewBox="0 0 24 24"
                    className="w-13 h-13 drop-shadow-[2px_2px_0px_#261608]"
                    style={{ shapeRendering: 'crispEdges' }}
                  >
                    {/* Feather wings */}
                    <rect x="4" y="2" width="4" height="2" fill="#ca8a04" />
                    <rect x="3" y="4" width="6" height="4" fill="#facc15" />
                    <rect x="5" y="5" width="3" height="3" fill="#ffffff" />
                    {/* Golden Handle Stem */}
                    <rect x="8" y="7" width="5" height="8" fill="#8b5327" />
                    <rect x="9" y="8" width="3" height="6" fill="#d97706" />
                    <rect x="9" y="9" width="1" height="4" fill="#fef08a" />
                    {/* Gem Grip Band */}
                    <rect x="8" y="15" width="5" height="2" fill="#261608" />
                    <rect x="9" y="15" width="3" height="2" fill="#e11d48" />
                    {/* Crystal Touch Nib (Clicker Point) */}
                    <rect x="9" y="17" width="3" height="3" fill="#38bdf8" />
                    <rect x="10" y="20" width="1" height="2" fill="#ffffff" />
                  </svg>
                  {/* Floating Sparkle Pixels */}
                  <span className="absolute -top-1 -right-1 text-[10px] animate-pulse">
                    ✨
                  </span>
                </div>
              </div>

              {/* Tool Stats & Info */}
              <div className="flex-1 flex flex-col justify-between min-w-0">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <h2 className="font-pixel text-sm font-black text-[#261608]">
                      클리커 Lv.{clickerLevel}
                    </h2>
                  </div>
                  {/* 단계표 기준 기본 세기 */}
                  <div className="bg-[#fef08a] border-2 border-[#261608] px-1.5 py-0.5 shadow-[1px_1px_0px_#261608] flex items-center gap-1">
                    <span className="w-1.5 h-1.5 bg-[#e11d48]" />
                    <span className="font-pixel text-[11px] font-black text-[#261608]">
                      기본 클릭당 {clickerPower}점
                    </span>
                  </div>
                </div>

                {/* 다음 강화 비용 대비 보유 기여도 */}
                <div className="mt-2">
                  <div className="flex items-center justify-between text-[10px] font-pixel mb-1">
                    <span className="font-bold text-[#8b5327]">강화 비용 대비 보유 기여도</span>
                    <span className="font-press-start text-[9px] font-bold text-[#261608]">
                      {upgradeCost === null ? 'MAX' : `${contribution.toLocaleString('ko-KR')} / ${upgradeCost.toLocaleString('ko-KR')}점`}
                    </span>
                  </div>

                  {/* 16-bit Segmented Progress Bar */}
                  <div className="w-full h-4 bg-[#edd9b0] border-2 border-[#261608] shadow-[inset_1px_1px_2px_rgba(0,0,0,0.2)] p-0.5 relative overflow-hidden">
                    {/* 잔액이 강화 비용 이상이면 100%, 최고 단계도 100% */}
                    <div
                      className="h-full bg-[#facc15] border-r-2 border-[#261608] transition-all duration-300 relative"
                      style={{ width: `${upgradeCost === null ? 100 : Math.min(100, contribution / upgradeCost * 100)}%` }}
                    >
                      {/* Pixel hash lines */}
                      <div className="w-full h-full opacity-30 bg-[linear-gradient(45deg,#261608_25%,transparent_25%,transparent_50%,#261608_50%,#261608_75%,transparent_75%,transparent)] bg-[size:6px_6px]" />
                    </div>
                  </div>
                </div>

                {/* 개인 기여도 차감 강화 */}
                {/* Wraps the button below on narrow screens instead of cutting the preview (M6-D1). */}
                <div className="mt-2 flex flex-wrap items-center justify-end gap-x-2 gap-y-1">
                  <span data-testid="clicker-upgrade-preview" className="mr-auto text-[9px] font-pixel text-[#8b5327] font-bold whitespace-nowrap">
                    {game.nextClicker ? `${clickerPower} → ${game.nextClicker.power}점` : '최고 단계'}
                  </span>
                  <button
                    type="button"
                    onClick={handleUpgradeClicker}
                    disabled={!canUpgrade || upgrading}
                    className={`pixel-plaque px-3 py-1 text-center font-pixel text-xs font-bold flex items-center gap-1.5 shrink-0 cursor-pointer ${
                      canUpgrade ? 'plaque-green' : 'plaque-parchment text-[#261608] opacity-60 cursor-not-allowed'
                    }`}
                  >
                    <span>{upgrading ? '요청 중…' : '업그레이드'}</span>
                    <span className="font-press-start text-[8px] bg-[#facc15] text-[#261608] border border-[#261608] px-1 py-0.2">
                      {upgradeCost === null ? 'MAX' : `${upgradeCost.toLocaleString('ko-KR')}점`}
                    </span>
                  </button>
                </div>
              </div>
            </div>
          </div>

          {requestError?.key === 'shop-upgrade' && (
            <p role="alert" className="font-pixel text-[10px] font-bold text-[#b91c1c] bg-[#fee2e2] border border-[#b91c1c] px-1.5 py-1">
              {requestError.message}
            </p>
          )}
          <div className="rpg-box p-2 font-pixel text-[10px] text-[#8b5327]" role="status">
            <p>수치는 목업용 임시 값 · 개인 기여도만 소비 · 구매로 레벨 하락 없음</p>
            <p>{game.activeBuff ? `${game.activeBuff.name} · ${game.remainingSeconds}초 남음` : '활성 버프 없음'} · 현재 클릭당 {game.normalPower}점</p>
            <p>버프는 1개만 활성화 · 새로 사용하면 기존 효과 교체</p>
            {upgradeCost !== null && contribution < upgradeCost && <p>강화까지 {(upgradeCost - contribution).toLocaleString('ko-KR')}점 부족</p>}
          </div>

          {/* ========================================================= */}
          {/* WOODEN SHELVES SECTION HEADER                             */}
          {/* ========================================================= */}
          <div className="flex items-center justify-between px-1">
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 bg-[#facc15] border border-[#261608] flex items-center justify-center font-press-start text-[6px]">
                ★
              </div>
              <span className="font-pixel text-xs font-black text-[#fef3c7] drop-shadow-[1px_1px_0px_#261608]">
                원목 선반 진열대
              </span>
            </div>
            <span className="font-pixel text-[9px] text-[#fde68a]">
              클릭하여 물품 선택
            </span>
          </div>

          {/* ========================================================= */}
          {/* 2-COLUMN GRID OF ITEM CARDS ON WOODEN SHELVES             */}
          {/* 적용 가능한 버프와 정책 미확정 기존 시안을 함께 보존 */}
          {/* ========================================================= */}
          <div className="relative">
            {/* Wooden Shelf Backboard Planks */}
            <div className="grid grid-cols-2 gap-2 relative z-10">
              {storeItems.map((item) => {
                const isSelected = item.id === selectedItemId;

                return (
                  <button
                    type="button"
                    key={item.id}
                    onClick={() => handleSelectItem(item.id)}
                    aria-label={`${item.name} ${item.previewOnly ? '시안 보기' : '구매 선택'}`}
                    data-selected={isSelected || undefined}
                    className={`game-shelf-card text-left cursor-pointer transition-all duration-100 flex flex-col justify-between p-2 relative bg-[#fdf5df] border-3 ${
                      isSelected
                        ? 'border-[#facc15] shadow-[0_0_0_2px_#261608,3px_3px_0px_#261608,0_0_10px_rgba(250,204,21,0.9)] scale-[1.02]'
                        : 'border-[#261608] shadow-[2px_2px_0px_#261608] hover:bg-[#fffbeb]'
                    }`}
                  >
                    {isSelected && (
                      <div className="game-shelf-selected absolute -top-2.5 -right-2 bg-[#facc15] text-[#261608] border border-[#261608] px-1 py-0.2 font-pixel text-[9px] font-black shadow-[1px_1px_0px_#261608] flex items-center gap-0.5 z-20 animate-pulse">
                        <span>선택됨</span><span>👈</span>
                      </div>
                    )}
                    <div className="game-shelf-card-body flex items-start gap-2">
                      {renderPixelIcon(item.iconType)}
                      <div className="game-shelf-card-copy min-w-0 flex-1">
                        <h3 className="game-shelf-card-name font-pixel text-xs font-black text-[#261608] leading-tight truncate">{item.name}</h3>
                        <p className="game-shelf-card-description font-pixel text-[9px] text-[#8b5327] mt-0.5 leading-tight line-clamp-2">{item.description}</p>
                      </div>
                    </div>
                    <div className="mt-2 pt-1.5 border-t border-[#eeddb6] flex items-center justify-between w-full">
                      <span className="font-pixel text-[9px] text-[#8b5327] font-bold">{item.previewOnly ? '검토 중' : '가격'}</span>
                      <div className="flex items-center gap-1 bg-[#fef08a] border border-[#261608] px-1.5 py-0.5">
                        <span className="font-press-start text-[8px] font-bold text-[#261608]">{item.cost}</span>
                        <span className="font-pixel text-[8px] font-bold text-[#8b5327]">점</span>
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>

            {/* Visual Wooden Shelf Ledge Beneath Row 1 & Row 2 */}
            <div className="w-full h-3 bg-[#8b5327] border-2 border-[#261608] mt-1 shadow-[2px_2px_0px_#381e09] flex items-center justify-between px-2">
              <span className="w-1.5 h-1.5 bg-[#d97706] border border-[#261608]" />
              <span className="w-16 h-1 bg-[#5a3012]" />
              <span className="w-1.5 h-1.5 bg-[#d97706] border border-[#261608]" />
            </div>
          </div>

          <ImageChangeShop onConfirmationChange={setImageConfirming} />

          {/* ========================================================= */}
          {/* CONFIRMATION DIALOG BOX FOR SELECTED CARD                 */}
          {/* 구매 비용·잔액·버프 교체 안내 또는 미확정 시안 설명 */}
          {/* ========================================================= */}
          {showConfirmDialog && selectedItem && (
            <div ref={confirmRef} role="dialog" aria-label="아이템 구매 확인" className="rpg-box p-2.5 bg-[#fffbeb] border-3 border-[#261608] shadow-[3px_3px_0px_#261608] flex flex-col gap-2 mt-0.5 animate-fadeIn">
              {/* Dialog Header */}
              <div className="flex items-center justify-between border-b-2 border-[#eeddb6] pb-1">
                <div className="flex items-center gap-1.5">
                  <div className="w-4 h-4 bg-[#fb7185] border border-[#261608] flex items-center justify-center text-white">
                    <AlertTriangle className="w-2.5 h-2.5 text-white" />
                  </div>
                  <span className="font-pixel text-xs font-black text-[#261608]">
                    {selectedItem.previewOnly ? '검토 중인 시안' : '구매 확인'} · {selectedItem.name} ({selectedItem.cost}점)
                  </span>
                </div>
                <button
                  onClick={handleCancelPurchase}
                  className="text-[#8b5327] hover:text-[#261608] cursor-pointer"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              </div>

              {/* 구매 후 잔액과 효과 교체를 명시한다. */}
              <div className="p-1.5 bg-[#faecc7] border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex items-center gap-2">
                <span className="text-base shrink-0">⚠️</span>
                <p className="font-pixel text-xs font-bold text-[#b45309] leading-tight">
                  {selectedItem.previewOnly ? '사용 정책이 정해지지 않아 구매할 수 없는 기존 시안입니다.' : contribution < selectedItem.cost ? '기여도가 부족합니다.' : `구매 후 기여도 ${(contribution - selectedItem.cost).toLocaleString('ko-KR')}점. 기존 버프가 교체되며 클리커 레벨은 유지됩니다.`}
                </p>
              </div>

              {requestError?.key === 'shop-buy' && (
                <p role="alert" className="font-pixel text-[10px] font-bold text-[#b91c1c] bg-[#fee2e2] border border-[#b91c1c] px-1.5 py-1">
                  {requestError.message}
                </p>
              )}
              {/* Two Pixel Buttons: "예 / 아니오" */}
              <div className="grid grid-cols-2 gap-2 pt-0.5">
                <button
                  onClick={handleConfirmPurchase}
                  disabled={selectedItem.previewOnly || contribution < selectedItem.cost || buying}
                  className="disabled:opacity-50 disabled:cursor-not-allowed pixel-plaque plaque-green py-1.5 px-2 text-center font-pixel text-xs font-black flex items-center justify-center gap-1 cursor-pointer"
                >
                  <Check className="w-3.5 h-3.5 text-white" />
                  <span>{selectedItem.previewOnly ? '정책 검토 중' : buying ? '요청 중…' : '구매 및 사용'}</span>
                </button>

                <button
                  onClick={handleCancelPurchase}
                  className="pixel-plaque plaque-parchment py-1.5 px-2 text-center font-pixel text-xs font-bold text-[#261608] flex items-center justify-center gap-1 cursor-pointer"
                >
                  <X className="w-3.5 h-3.5 text-[#261608]" />
                  <span>아니오</span>
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ========================================================= */}
      {/* 4. BOTTOM NAVIGATION BAR: "상점" TAB ACTIVE               */}
      {/* ========================================================= */}
      {!embedded && <div className="w-full px-2 pb-2 bg-[#fdf5df] border-t-2 border-[#261608] shrink-0">
        <BottomNav
          activeTab="shop"
          onChangeTab={(tab) => {
            soundManager.playTabSound();
            onNavigateTab(tab);
          }}
          hasShopUpgrade={false}
          hasRankChange={false}
        />
      </div>}
    </ShopFrame>
  );
};
