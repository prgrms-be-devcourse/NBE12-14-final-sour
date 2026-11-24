interface PageLock {
  count: number;
  restore: () => void;
}

// A document-scoped resource, not player/game state. Overlapping modals share one lock.
const locks = new WeakMap<Document, PageLock>();

function overrideStyles(style: CSSStyleDeclaration, values: Record<string, string>) {
  const previous = Object.keys(values).map(property => ({
    property, value: style.getPropertyValue(property), priority: style.getPropertyPriority(property),
  }));
  for (const [property, value] of Object.entries(values)) style.setProperty(property, value, 'important');
  return () => {
    for (const { property, value, priority } of previous) {
      if (value) style.setProperty(property, value, priority);
      else style.removeProperty(property);
    }
  };
}

/** Native dialog inertness does not lock page scrolling. Keep the background
 * visually stationary, including touch browsers, without disabling sheet gestures.
 * The last release restores only the styles we changed and the original page offset.
 */
export function lockPageScroll(document: Document): () => void {
  const view = document.defaultView;
  if (!view) return () => {};
  let lock = locks.get(document);
  if (!lock) {
    const { scrollX, scrollY } = view;
    const root = document.documentElement;
    const body = document.body;
    const restoreRoot = overrideStyles(root.style, { overflow: 'hidden', 'overscroll-behavior': 'none' });
    const restoreBody = overrideStyles(body.style, {
      position: 'fixed', top: `${-scrollY}px`, left: `${-scrollX}px`, width: '100%', overflow: 'hidden',
    });
    lock = {
      count: 0,
      restore() {
        // Avoid a site's smooth-scroll setting animating the restoration.
        const restoreBehavior = overrideStyles(root.style, { 'scroll-behavior': 'auto' });
        restoreBody();
        restoreRoot();
        if (view.scrollX !== scrollX || view.scrollY !== scrollY) view.scrollTo(scrollX, scrollY);
        restoreBehavior();
      },
    };
    locks.set(document, lock);
  }
  lock.count++;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    if (--lock.count === 0) {
      locks.delete(document);
      lock.restore();
    }
  };
}
