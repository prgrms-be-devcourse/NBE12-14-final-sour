import {createRoot} from 'react-dom/client';
import App from './App.tsx';
import './index.css';
import { ApiProvider } from './api/ApiProvider';
import { API_MODE } from './api/mode';
import { MockGameProvider } from './hooks/useMockGame';

function mount() {
  const root = createRoot(document.getElementById('root')!);
  // Keep the build constant at the import site so the service bundle omits these entry chunks.
  if (__CAMPUS_MOCK_TOOLS__) {
    const params = new URLSearchParams(window.location.search);
    const shotLevel = params.get('campusShot');
    if (shotLevel) {
      void import('./components/CampusShot').then(({ CampusShot }) => root.render(<CampusShot level={Number(shotLevel)} />));
      return;
    }
    if (params.has('apiCheck')) {
      void import('./components/ApiCheckScreen').then(({ ApiCheckScreen }) => root.render(<ApiProvider><ApiCheckScreen /></ApiProvider>));
      return;
    }
  }
  const app = API_MODE === 'api' ? <App /> : <MockGameProvider><App /></MockGameProvider>;
  root.render(<ApiProvider>{app}</ApiProvider>);
}

mount();
