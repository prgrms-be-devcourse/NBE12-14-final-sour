import { useRef, useState, useEffect, useCallback } from 'react';

interface UseDragScrollOptions {
  onScrollBottom?: () => void;
  bottomThreshold?: number; // Distance in px from bottom to trigger onScrollBottom
}

export function useDragScroll({
  onScrollBottom,
  bottomThreshold = 100,
}: UseDragScrollOptions = {}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [isDragging, setIsDragging] = useState<boolean>(false);
  const [scrollTop, setScrollTop] = useState<number>(0);
  const [hasScrolledPastTop, setHasScrolledPastTop] = useState<boolean>(false);

  // Drag tracking refs
  const isPointerDownRef = useRef<boolean>(false);
  const startYRef = useRef<number>(0);
  const startScrollTopRef = useRef<number>(0);
  const totalDragDistanceRef = useRef<number>(0);
  const lastYRef = useRef<number>(0);
  const lastTimeRef = useRef<number>(0);
  const velocityYRef = useRef<number>(0);
  const animationFrameRef = useRef<number | null>(null);
  const isDraggingStateRef = useRef<boolean>(false);

  // Sync scroll position & detect bottom
  const handleScroll = useCallback(() => {
    if (!containerRef.current) return;
    const el = containerRef.current;
    const currentTop = el.scrollTop;
    setScrollTop(currentTop);
    setHasScrolledPastTop(currentTop > 140);

    const isNearBottom = el.scrollHeight - (currentTop + el.clientHeight) <= bottomThreshold;
    if (isNearBottom && onScrollBottom) {
      onScrollBottom();
    }
  }, [onScrollBottom, bottomThreshold]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    el.addEventListener('scroll', handleScroll, { passive: true });
    return () => {
      el.removeEventListener('scroll', handleScroll);
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
      }
    };
  }, [handleScroll]);

  const stopInertia = () => {
    if (animationFrameRef.current) {
      cancelAnimationFrame(animationFrameRef.current);
      animationFrameRef.current = null;
    }
  };

  const startInertia = (initialVelocity: number) => {
    stopInertia();
    let currentVelocity = initialVelocity;
    const friction = 0.94; // Smooth deceleration

    const step = () => {
      if (!containerRef.current) return;
      currentVelocity *= friction;

      if (Math.abs(currentVelocity) < 0.05) {
        animationFrameRef.current = null;
        return;
      }

      containerRef.current.scrollTop -= currentVelocity * 16;
      animationFrameRef.current = requestAnimationFrame(step);
    };

    animationFrameRef.current = requestAnimationFrame(step);
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    // Only primary mouse button (left-click) or touch
    if (e.button !== 0) return;

    stopInertia();
    isPointerDownRef.current = true;
    startYRef.current = e.clientY;
    lastYRef.current = e.clientY;
    lastTimeRef.current = performance.now();
    velocityYRef.current = 0;
    totalDragDistanceRef.current = 0;

    if (containerRef.current) {
      startScrollTopRef.current = containerRef.current.scrollTop;
    }
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!isPointerDownRef.current || !containerRef.current) return;

    const deltaY = e.clientY - startYRef.current;
    const now = performance.now();
    const dt = now - lastTimeRef.current;

    if (dt > 8) {
      velocityYRef.current = (e.clientY - lastYRef.current) / dt;
      lastYRef.current = e.clientY;
      lastTimeRef.current = now;
    }

    totalDragDistanceRef.current += Math.abs(e.movementY || 0);

    // If dragged more than 5px, enter drag mode
    if (Math.abs(deltaY) > 5) {
      if (!isDraggingStateRef.current) {
        isDraggingStateRef.current = true;
        setIsDragging(true);
      }
      containerRef.current.scrollTop = startScrollTopRef.current - deltaY;
    }
  };

  const handlePointerUp = () => {
    if (!isPointerDownRef.current) return;
    isPointerDownRef.current = false;

    if (isDraggingStateRef.current) {
      // If user flicked with velocity, trigger momentum
      if (Math.abs(velocityYRef.current) > 0.2) {
        startInertia(velocityYRef.current);
      }

      // Briefly keep isDragging true to prevent accidental click handler triggers
      setTimeout(() => {
        isDraggingStateRef.current = false;
        setIsDragging(false);
      }, 50);
    } else {
      isDraggingStateRef.current = false;
      setIsDragging(false);
    }
  };

  const scrollToTop = useCallback(() => {
    stopInertia();
    if (containerRef.current) {
      containerRef.current.scrollTo({
        top: 0,
        behavior: 'smooth',
      });
    }
  }, []);

  return {
    containerRef,
    isDragging,
    scrollTop,
    hasScrolledPastTop,
    scrollToTop,
    dragHandlers: {
      onPointerDown: handlePointerDown,
      onPointerMove: handlePointerMove,
      onPointerUp: handlePointerUp,
      onPointerCancel: handlePointerUp,
    },
  };
}
