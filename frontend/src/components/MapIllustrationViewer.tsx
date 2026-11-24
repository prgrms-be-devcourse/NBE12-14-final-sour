import React, { useState } from 'react';
import koreaMapImage from '../assets/images/korea_pixel_map_1793879803724.jpg';
import { ZoomIn, ZoomOut, Download, Sparkles, Map, Eye } from 'lucide-react';
import { soundManager } from '../utils/sound';

interface MapIllustrationViewerProps {
  onGoToMapScreen?: () => void;
}

export const MapIllustrationViewer: React.FC<MapIllustrationViewerProps> = ({
  onGoToMapScreen,
}) => {
  const [zoomLevel, setZoomLevel] = useState<number>(1);
  const [showDetails, setShowDetails] = useState<boolean>(true);

  const handleZoom = (delta: number) => {
    soundManager.playTabSound();
    setZoomLevel((prev) => Math.min(2.0, Math.max(0.8, Number((prev + delta).toFixed(1)))));
  };

  const handleResetZoom = () => {
    soundManager.playTabSound();
    setZoomLevel(1);
  };

  return (
    <div className="w-full flex flex-col items-center py-2 animate-fadeIn select-none">
      {/* Top Presentation Bar */}
      <div className="w-full max-w-4xl flex flex-col sm:flex-row items-center justify-between gap-2 mb-3 px-2">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 bg-[#38bdf8] border-2 border-[#261608] flex items-center justify-center shadow-[1px_1px_0px_#261608]">
            <Map className="w-4 h-4 text-[#261608]" />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <h2 className="font-pixel text-sm sm:text-base font-black text-[#261608]">
                대한민국 16비트 도트 배경 맵 일러스트 (1080 × 1600)
              </h2>
              <span className="font-pixel text-[10px] text-white bg-[#0284c7] border border-[#261608] px-1.5 py-0.2 shadow-[1px_1px_0px_#261608]">
                NO UI · 순수 일러스트
              </span>
            </div>
            <p className="font-pixel text-[10px] text-[#8b5327]">
              텍스트·마커·UI가 전혀 없는 순수 16비트 오버월드 지형 일러스트레이션
            </p>
          </div>
        </div>

        {/* Controls: Zoom In / Out / Reset / Map with UI */}
        <div className="flex items-center gap-1.5">
          <div className="flex items-center gap-1 bg-[#fdf5df] border-2 border-[#261608] p-1 shadow-[2px_2px_0px_#261608]">
            <button
              onClick={() => handleZoom(-0.2)}
              className="pixel-plaque plaque-parchment p-1 text-xs cursor-pointer"
              title="축소"
            >
              <ZoomOut className="w-3.5 h-3.5 text-[#261608]" />
            </button>
            <span className="font-press-start text-[9px] text-[#261608] px-1">
              {Math.round(zoomLevel * 100)}%
            </span>
            <button
              onClick={() => handleZoom(0.2)}
              className="pixel-plaque plaque-parchment p-1 text-xs cursor-pointer"
              title="확대"
            >
              <ZoomIn className="w-3.5 h-3.5 text-[#261608]" />
            </button>
            <button
              onClick={handleResetZoom}
              className="pixel-plaque plaque-parchment px-1.5 py-0.5 text-[9px] font-pixel font-bold cursor-pointer"
            >
              1:1
            </button>
          </div>

          {onGoToMapScreen && (
            <button
              onClick={onGoToMapScreen}
              className="pixel-plaque plaque-yellow px-2 py-1 text-xs font-pixel font-bold flex items-center gap-1 text-[#261608] cursor-pointer"
            >
              <Eye className="w-3.5 h-3.5 text-[#261608]" />
              <span>UI 탑재 지도 보기</span>
            </button>
          )}

          <a
            href={koreaMapImage}
            download="daedaedae_korea_pixel_map.jpg"
            className="pixel-plaque plaque-green px-2 py-1 text-xs font-pixel font-bold flex items-center gap-1 text-white cursor-pointer"
          >
            <Download className="w-3.5 h-3.5 text-white" />
            <span>다운로드</span>
          </a>
        </div>
      </div>

      {/* Main Illustration Frame (Portrait 1080x1600 Ratio, 9:13.3) */}
      <div className="w-full max-w-[540px] max-h-[800px] overflow-auto border-4 border-[#261608] bg-[#38bdf8] shadow-[8px_8px_0px_#261608] relative p-1 flex justify-center items-center">
        <div
          className="transition-transform duration-150 origin-center relative"
          style={{ transform: `scale(${zoomLevel})` }}
        >
          <img
            src={koreaMapImage}
            alt="대한민국 16비트 도트 배경 맵 일러스트 (1080x1600)"
            className="w-full h-auto max-w-[520px] aspect-[1080/1600] object-contain pixelated border-2 border-[#261608] shadow-[2px_2px_0px_#261608]"
            referrerPolicy="no-referrer"
          />
        </div>
      </div>

      {/* Feature Annotation Plaque */}
      {showDetails && (
        <div className="w-full max-w-4xl mt-3 rpg-box p-3 bg-[#fdf5df] border-3 border-[#261608] shadow-[3px_3px_0px_#261608]">
          <div className="flex items-center justify-between border-b-2 border-[#eeddb6] pb-1.5 mb-2">
            <div className="flex items-center gap-1.5">
              <Sparkles className="w-4 h-4 text-[#d97706]" />
              <h3 className="font-pixel text-xs sm:text-sm font-black text-[#261608]">
                16비트 도트 맵 지형 디테일 분석
              </h3>
            </div>
            <span className="font-pixel text-[10px] text-[#8b5327] font-bold">
              해상도: 1080 × 1600 (세로형)
            </span>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 text-[10px] font-pixel text-[#261608]">
            <div className="p-1.5 bg-[#ffffff] border border-[#261608]/40 shadow-[1px_1px_0px_#261608]">
              <span className="font-black text-[#0284c7] block mb-0.5">
                🌊 해안선 & 도서 지형
              </span>
              <p className="text-[#8b5327] leading-tight">
                서해안·남해안의 복잡한 리아스식 군도와 갯벌 디더링, 동해안의 매끄러운 해안선 및 독도·울릉도, 서남단 제주도가 온전히 구현되어 있습니다.
              </p>
            </div>

            <div className="p-1.5 bg-[#ffffff] border border-[#261608]/40 shadow-[1px_1px_0px_#261608]">
              <span className="font-black text-[#15803d] block mb-0.5">
                ⛰️ 산맥 & 하천 수계
              </span>
              <p className="text-[#8b5327] leading-tight">
                동쪽을 따라 뻗은 태백산맥 능선과 설산 봉우리, 서울을 가로지르는 한강 줄기 및 영남을 굽이치는 낙동강 수계가 선명한 픽셀로 흐릅니다.
              </p>
            </div>

            <div className="p-1.5 bg-[#ffffff] border border-[#261608]/40 shadow-[1px_1px_0px_#261608]">
              <span className="font-black text-[#b45309] block mb-0.5">
                🏘️ 주요 도시 마을 클러스터
              </span>
              <p className="text-[#8b5327] leading-tight">
                텍스트 라벨 없이 서울, 대전, 대구, 광주, 부산, 강릉, 제주시 위치에 아늑한 오렌지/청색 기와지붕 도트 미니어처 마을들이 오밀조밀 모여 있습니다.
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
