import { useEffect, useState } from 'preact/hooks';
import type { Observable } from '../services.ts';

export function useObservable<T>(o: Observable<T>): T {
  const [v, setV] = useState(o.get());
  useEffect(() => {
    setV(o.get());
    return o.subscribe(setV);
  }, [o]);
  return v;
}

export const navigate = (hash: string) => {
  location.hash = hash;
};
