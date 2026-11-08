//! Transition setup, GamePro.exe sub_A1F130. Capture/execution are separate opcodes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TransitionConfig {
    pub mask_id: i32,
    pub duration: i32,
    pub flag: bool,
}
impl TransitionConfig {
    pub fn decode(params: &[i32]) -> Result<Self, String> {
        if params.len() < 2 { return Err("transition setup requires mask and timing".into()); }
        if params[0] == -2 { return Err("transition mask reload is not implemented".into()); }
        Ok(Self { mask_id: params[0],
            duration: params.get(2).copied().unwrap_or(params[1] & 0xFFFF),
            flag: params[1] & 0x10000 != 0 })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn packed_and_extended_transition_timing_match_dispatcher_layout() {
        assert_eq!(TransitionConfig::decode(&[-1, 65546]).unwrap(), TransitionConfig { mask_id: -1, duration: 10, flag: true });
        assert_eq!(TransitionConfig::decode(&[3, 0, 70000]).unwrap(), TransitionConfig { mask_id: 3, duration: 70000, flag: false });
        assert!(TransitionConfig::decode(&[-2, 0]).is_err());
        assert!(TransitionConfig::decode(&[-1]).is_err());
    }
}
