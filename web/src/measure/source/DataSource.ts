/**
 * The single interface the UI talks to for a live measurement source.
 *
 * A source reads a pulse — the rear camera with a fingertip over the lens — and
 * emits PPG samples plus contact and motion updates. If the source can't run in
 * this environment (desktop with no camera, blocked permission), it settles into
 * an 'unsupported' state with a friendly message instead of throwing.
 */
import type { PpgPacket } from './ppgTypes';
import { Emitter } from '../util/emitter';

export type ConnectionState =
  | 'idle'
  | 'unsupported'
  | 'starting'
  | 'measuring'
  | 'stopped';

export interface DataSourceEvents {
  /** A batch of camera pulse samples (inverted red = pulse). */
  ppg: (p: PpgPacket) => void;
  /** Raw per-frame channel means (0–255) for the contact-quality score. */
  frame: (redMean: number, greenMean: number) => void;
  connection: (state: ConnectionState, detail?: { message?: string }) => void;
}

export interface DataSource {
  readonly state: ConnectionState;
  /** True when the phone's torch was turned on for a brighter, steadier reading. */
  readonly torchOn: boolean;
  /** Measured capture frame rate (Hz), or null before the first frames arrive. */
  readonly frameRate: number | null;

  start(): Promise<void>;
  stop(): Promise<void>;

  on<K extends keyof DataSourceEvents>(event: K, cb: DataSourceEvents[K]): () => void;
}

/** Shared event plumbing + connection-state tracking for a data source. */
export abstract class BaseDataSource implements DataSource {
  protected emitter = new Emitter<DataSourceEvents>();
  protected _state: ConnectionState = 'idle';
  abstract readonly torchOn: boolean;
  abstract readonly frameRate: number | null;

  get state(): ConnectionState {
    return this._state;
  }

  protected setState(state: ConnectionState, detail?: { message?: string }): void {
    this._state = state;
    this.emitter.emit('connection', state, detail);
  }

  on<K extends keyof DataSourceEvents>(event: K, cb: DataSourceEvents[K]): () => void {
    return this.emitter.on(event, cb);
  }

  abstract start(): Promise<void>;
  abstract stop(): Promise<void>;
}
