"use client";

/**
 * Voice input through the browser's speech recognition (Chrome, Edge, Safari; not Firefox).
 *
 * - The audio is turned into text by the browser's speech service (Google for Chrome), so it needs
 *   an internet connection and a secure page (https or localhost).
 * - The voice language is chosen separately from the UI language, because staff often use the
 *   portal in one language and speak customer or product names in another. No saved choice means
 *   "same as the UI language".
 */

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useLanguage, type Lang } from "@/lib/i18n";

/** The recognition locale for each portal language. */
export const speechLocales: Record<Lang, string> = { en: "en-US", ar: "ar-SA", bn: "bn-BD", hi: "hi-IN" };

// The Web Speech API is not in TypeScript's DOM library yet: just the parts used here.
type RecognitionResult = { isFinal: boolean; 0: { transcript: string } };
type RecognitionEvent = { resultIndex: number; results: { length: number; [i: number]: RecognitionResult } };
type Recognition = {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  maxAlternatives: number;
  onresult: ((e: RecognitionEvent) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
};
type RecognitionCtor = new () => Recognition;

function recognitionCtor(): RecognitionCtor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

const noSubscribe = () => () => {};

/** False on the server and in browsers without speech recognition, so mic buttons can hide. */
export function useSpeechSupported() {
  return useSyncExternalStore(noSubscribe, () => recognitionCtor() != null, () => false);
}

const STORAGE_KEY = "fatoora.voiceLanguage";
const CHANGE_EVENT = "fatoora-voice-language-change";
let memory: Lang | null = null;

function readStored(): Lang | null {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    return v === "en" || v === "bn" || v === "hi" || v === "ar" ? v : null;
  } catch {
    return memory;
  }
}

function subscribe(onChange: () => void) {
  window.addEventListener(CHANGE_EVENT, onChange);
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange);
    window.removeEventListener("storage", onChange);
  };
}

/** The language staff speak in: the saved voice language, or the UI language. */
export function useVoiceLanguage(): [Lang, (lang: Lang) => void] {
  const { lang } = useLanguage();
  const saved = useSyncExternalStore(subscribe, readStored, () => null);
  const set = useCallback((next: Lang) => {
    memory = next;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Storage blocked: the choice still applies in this tab.
    }
    window.dispatchEvent(new Event(CHANGE_EVENT));
  }, []);
  return [saved ?? lang, set];
}

/** English messages (translation keys) for the recognition errors staff can do something about. */
const errorMessages: Record<string, string> = {
  "not-allowed": "Microphone access is blocked. Allow it in the browser's address bar.",
  "service-not-allowed": "Microphone access is blocked. Allow it in the browser's address bar.",
  "audio-capture": "No microphone found.",
  "no-speech": "Didn't hear anything. Tap the mic and speak again.",
  network: "Voice input needs an internet connection.",
  "language-not-supported": "This browser can't recognise that voice language.",
};

/**
 * One utterance at a time: start() listens until the speaker pauses, then onText gets the final
 * text. `interim` shows the words as they are recognised.
 */
export function useSpeech(onText: (text: string) => void) {
  const [voiceLang] = useVoiceLanguage();
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState("");
  const [error, setError] = useState<string | null>(null);
  const rec = useRef<Recognition | null>(null);
  const callback = useRef(onText);
  useEffect(() => {
    callback.current = onText;
  });

  useEffect(() => () => rec.current?.abort(), []);

  const stop = useCallback(() => rec.current?.stop(), []);

  const start = useCallback(() => {
    const Ctor = recognitionCtor();
    if (!Ctor) return;
    rec.current?.abort();
    const r = new Ctor();
    r.lang = speechLocales[voiceLang];
    r.interimResults = true;
    r.continuous = false;
    r.maxAlternatives = 1;
    let final = "";
    r.onresult = (e) => {
      let partial = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const res = e.results[i];
        if (res.isFinal) final += res[0].transcript;
        else partial += res[0].transcript;
      }
      setInterim((final + " " + partial).trim());
    };
    r.onerror = (e) => {
      // "aborted" is our own abort(): nothing to tell the user.
      if (e.error !== "aborted") setError(errorMessages[e.error] ?? "Voice input failed. Try again.");
    };
    r.onend = () => {
      setListening(false);
      setInterim("");
      if (rec.current === r) rec.current = null;
      if (final.trim()) callback.current(final.trim());
    };
    rec.current = r;
    setError(null);
    setInterim("");
    setListening(true);
    try {
      r.start();
    } catch {
      setListening(false);
      setError("Voice input failed. Try again.");
    }
  }, [voiceLang]);

  return { listening, interim, error, start, stop, toggle: listening ? stop : start };
}
