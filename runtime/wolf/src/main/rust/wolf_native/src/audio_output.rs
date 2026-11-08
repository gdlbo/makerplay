//! SDL3 playback of decoded interleaved float PCM. Device lifetime is owned here.
use std::ffi::{c_char, c_void, CStr};
use crate::audio::DecodedAudio;
#[repr(C)]
struct AudioSpec { format: u32, channels: i32, freq: i32 }
extern "C" {
    fn SDL_InitSubSystem(flags: u32) -> bool;
    fn SDL_QuitSubSystem(flags: u32);
    fn SDL_GetError() -> *const c_char;
    fn SDL_OpenAudioDeviceStream(device: u32, spec: *const AudioSpec,
        callback: Option<unsafe extern "C" fn(*mut c_void, *mut c_void, i32, i32)>, userdata: *mut c_void) -> *mut c_void;
    fn SDL_DestroyAudioStream(stream: *mut c_void);
    fn SDL_PutAudioStreamData(stream: *mut c_void, data: *const c_void, length: i32) -> bool;
    fn SDL_ResumeAudioStreamDevice(stream: *mut c_void) -> bool;
    fn SDL_GetAudioStreamAvailable(stream: *mut c_void) -> i32;
    fn SDL_FlushAudioStream(stream: *mut c_void) -> bool;
    fn SDL_GetAudioStreamQueued(stream: *mut c_void) -> i32;
    fn SDL_SetAudioStreamGain(stream: *mut c_void, gain: f32) -> bool;
}
const AUDIO: u32 = 0x10;
fn error() -> String { unsafe {
    let pointer = SDL_GetError();
    if pointer.is_null() { "SDL audio failed".into() } else { CStr::from_ptr(pointer).to_string_lossy().into_owned() }
} }

pub struct AudioPlayback { stream: *mut c_void, audio: DecodedAudio, looping: bool }
// No callbacks access this object. The owner serializes mutations; SDL streams
// permit calls from any thread, including destruction after ownership transfer.
unsafe impl Send for AudioPlayback {}
impl AudioPlayback {
    pub fn start(audio: DecodedAudio, looping: bool, gain: f32) -> Result<Self, String> {
        if audio.sample_rate == 0 || audio.sample_rate > i32::MAX as u32 || audio.channels == 0
            || audio.channels > 8 || audio.samples.is_empty() || !gain.is_finite() || gain < 0.0 {
            return Err("invalid SDL audio playback parameters".into());
        }
        unsafe {
            if !SDL_InitSubSystem(AUDIO) { return Err(error()); }
            let spec = AudioSpec { format: 0x8120, channels: audio.channels as i32, freq: audio.sample_rate as i32 };
            let stream = SDL_OpenAudioDeviceStream(u32::MAX, &spec, None, std::ptr::null_mut());
            if stream.is_null() { let message = error(); SDL_QuitSubSystem(AUDIO); return Err(message); }
            let playback = Self { stream, audio, looping };
            if !SDL_SetAudioStreamGain(stream, gain) { return Err(error()); }
            playback.enqueue()?;
            if !looping && !SDL_FlushAudioStream(stream) { return Err(error()); }
            if !SDL_ResumeAudioStreamDevice(stream) { return Err(error()); }
            Ok(playback)
        }
    }
    fn enqueue(&self) -> Result<(), String> {
        let bytes = self.audio.samples.len().checked_mul(4).and_then(|n| i32::try_from(n).ok())
            .ok_or("SDL audio queue too large")?;
        unsafe {
            if !SDL_PutAudioStreamData(self.stream, self.audio.samples.as_ptr().cast(), bytes) { return Err(error()); }
        }
        Ok(())
    }
    /// Returns false when a one-shot has drained. Loops refill ahead of draining.
    pub fn tick(&mut self) -> Result<bool, String> {
        let queued = unsafe { SDL_GetAudioStreamQueued(self.stream) };
        let available = unsafe { SDL_GetAudioStreamAvailable(self.stream) };
        if queued < 0 || available < 0 { return Err(error()); }
        let threshold = self.audio.sample_rate.saturating_mul(self.audio.channels).saturating_mul(4) / 4;
        if self.looping && (queued as u32) < threshold { self.enqueue()?; }
        Ok(self.looping || queued > 0 || available > 0)
    }
}
/// Playback collection owned by the native frame loop.
#[derive(Default)]
pub struct AudioMixer { voices: Vec<(i32, AudioPlayback, f32, Option<(i32,i32)>)> }
impl AudioMixer {
    pub fn play(&mut self, root: &std::path::Path, request: &crate::sound_command::SoundRequest) -> Result<(), String> {
        if request.resource_id == 0xFFFF {
            if request.fade_frames == 0 {
                self.voices.retain(|(channel,..)| *channel != request.channel);
            } else {
                for (channel,_,gain,fade) in &mut self.voices {
                    if *channel == request.channel {
                        if let Some((total,remaining))=*fade {
                            *gain *= remaining as f32 / total as f32;
                        }
                        let total=request.fade_frames.saturating_add(1);
                        *fade=Some((total,total));
                    }
                }
            }
            return Ok(());
        }
        if request.frequency_percent <= 0 || request.volume_percent < 0 {
            return Err("invalid sound volume/frequency".into());
        }
        if self.voices.len() >= 128 { return Err("native audio voice limit reached".into()); }
        if !crate::deployment_files::file_exists(root, &request.data_path)? {
            return Err(format!("sound file missing: {}", request.data_path));
        }
        let mut decoded = crate::audio::decode_file(&root.join(&request.data_path))?;
        decoded.sample_rate = u32::try_from(u64::from(decoded.sample_rate) * request.frequency_percent as u64 / 100)
            .map_err(|_| "sound frequency overflow")?;
        let voice = AudioPlayback::start(decoded, request.looping, request.volume_percent as f32 / 100.0)?;
        if request.looping { self.voices.retain(|(channel, ..)| *channel != request.channel); }
        self.voices.push((request.channel, voice, request.volume_percent as f32 / 100.0, None));
        eprintln!("wolf audio: started channel={} resource={} file={} voices={}",
            request.channel, request.resource_id, request.data_path, self.voices.len());
        Ok(())
    }
    pub fn tick(&mut self) -> Result<(), String> {
        let mut index = 0;
        while index < self.voices.len() {
            let (_,voice,gain,fade)=&mut self.voices[index];
            if let Some((total,remaining))=fade {
                *remaining-=1;
                if *remaining<=0 { self.voices.remove(index); continue; }
                if !unsafe { SDL_SetAudioStreamGain(voice.stream,*gain * (*remaining as f32 / *total as f32)) } {
                    return Err(error());
                }
            }
            if self.voices[index].1.tick()? { index += 1; }
            else { self.voices.remove(index); }
        }
        Ok(())
    }
}

impl Drop for AudioPlayback {
    fn drop(&mut self) { unsafe { SDL_DestroyAudioStream(self.stream); SDL_QuitSubSystem(AUDIO); } }
}
