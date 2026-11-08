//! Rust PCM decoding for the deployment's Ogg/Vorbis, WAV and MP3 audio.
use std::path::Path;
use symphonia::core::{audio::SampleBuffer, codecs::DecoderOptions, errors::Error,
    formats::FormatOptions, io::MediaSourceStream, meta::MetadataOptions, probe::Hint};

#[derive(Debug)]
pub struct DecodedAudio {
    pub sample_rate: u32,
    pub channels: u32,
    /// Interleaved normalized float PCM, ready for an SDL audio stream.
    pub samples: Vec<f32>,
}

pub fn decode_file(path: &Path) -> Result<DecodedAudio, String> {
    let file = std::fs::File::open(path).map_err(|error| format!("audio {}: {error}", path.display()))?;
    let mut hint = Hint::new();
    if let Some(extension) = path.extension().and_then(|value| value.to_str()) { hint.with_extension(extension); }
    decode_stream(MediaSourceStream::new(Box::new(file), Default::default()), &hint)
        .map_err(|error| format!("audio {}: {error}", path.display()))
}

fn decode_stream(stream: MediaSourceStream, hint: &Hint) -> Result<DecodedAudio, String> {
    let mut format = symphonia::default::get_probe().format(hint, stream,
        &FormatOptions::default(), &MetadataOptions::default()).map_err(|error| error.to_string())?.format;
    let track = format.default_track().ok_or("audio has no default track")?;
    let id = track.id;
    let mut decoder = symphonia::default::get_codecs().make(&track.codec_params, &DecoderOptions::default())
        .map_err(|error| error.to_string())?;
    let mut output = DecodedAudio { sample_rate: 0, channels: 0, samples: Vec::new() };
    loop {
        let packet = match format.next_packet() {
            Ok(packet) => packet,
            Err(Error::IoError(error)) if error.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(error) => return Err(error.to_string()),
        };
        if packet.track_id() != id { continue; }
        let decoded = decoder.decode(&packet).map_err(|error| error.to_string())?;
        let rate = decoded.spec().rate;
        let channels = decoded.spec().channels.count() as u32;
        if rate == 0 || channels == 0 { return Err("invalid audio sample format".into()); }
        if output.sample_rate != 0 && (output.sample_rate != rate || output.channels != channels) {
            return Err("audio changes sample format midstream".into());
        }
        output.sample_rate = rate;
        output.channels = channels;
        let mut samples = SampleBuffer::<f32>::new(decoded.capacity() as u64, *decoded.spec());
        samples.copy_interleaved_ref(decoded);
        if output.samples.len().saturating_add(samples.len()) > 32 * 1024 * 1024 {
            return Err("decoded audio exceeds 128 MiB limit".into());
        }
        output.samples.extend_from_slice(samples.samples());
    }
    if output.samples.is_empty() { return Err("audio contains no decoded samples".into()); }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn decodes_synthetic_pcm_wave_with_exact_channels_rate_and_samples() {
        let mut bytes = b"RIFF".to_vec();
        bytes.extend_from_slice(&44u32.to_le_bytes());
        bytes.extend_from_slice(b"WAVEfmt ");
        bytes.extend_from_slice(&16u32.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&2u16.to_le_bytes());
        bytes.extend_from_slice(&8000u32.to_le_bytes());
        bytes.extend_from_slice(&32000u32.to_le_bytes());
        bytes.extend_from_slice(&4u16.to_le_bytes());
        bytes.extend_from_slice(&16u16.to_le_bytes());
        bytes.extend_from_slice(b"data");
        bytes.extend_from_slice(&8u32.to_le_bytes());
        for sample in [i16::MIN, 0, 16384, i16::MAX] { bytes.extend_from_slice(&sample.to_le_bytes()); }
        let stream = MediaSourceStream::new(Box::new(std::io::Cursor::new(bytes)), Default::default());
        let audio = decode_stream(stream, Hint::new().with_extension("wav")).unwrap();
        assert_eq!((audio.sample_rate, audio.channels), (8000, 2));
        assert_eq!(audio.samples, vec![-1.0, 0.0, 0.5, 32767.0 / 32768.0]);
    }
    #[test]
    fn invalid_audio_is_an_error() {
        let stream = MediaSourceStream::new(Box::new(std::io::Cursor::new(vec![0; 64])), Default::default());
        assert!(decode_stream(stream, &Hint::new()).is_err());
    }
}
