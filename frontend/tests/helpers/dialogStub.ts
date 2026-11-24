import type { JSDOM } from 'jsdom';

/** jsdom 31 lacks showModal/close. This tests the API contract only, not the
 * browser's top layer, inertness, Tab navigation, Escape dispatch or layout.
 * Do not use this stub as evidence of actual browser modal behavior.
 */
export function installDialogStub(window: Pick<JSDOM['window'], 'HTMLDialogElement'>) {
  window.HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '');
    this.dataset.modal = 'true';
  };
  window.HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open');
    delete this.dataset.modal;
  };
}
