//! Move-route target IDs from GamePro.exe sub_A0ED70.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RouteTarget {
    /// Current-event target used by a runner with no map-event context.
    NoTarget,
    Hero,
    MapEvent(i32),
    Follower(u8),
}

pub fn resolve_target(raw: i32, current_event: Option<i32>) -> Result<RouteTarget, String> {
    match raw {
        -2 => Ok(RouteTarget::Hero),
        -1 => Ok(current_event.map(RouteTarget::MapEvent).unwrap_or(RouteTarget::NoTarget)),
        -7..=-3 => Ok(RouteTarget::Follower((-raw - 3) as u8)),
        0..=i32::MAX => Ok(RouteTarget::MapEvent(raw)),
        _ => Err(format!("unsupported move-route target {raw}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn current_event_and_hero_are_distinct_targets() {
        assert_eq!(resolve_target(-2, Some(8)).unwrap(), RouteTarget::Hero);
        assert_eq!(resolve_target(-1, Some(8)).unwrap(), RouteTarget::MapEvent(8));
        assert_eq!(resolve_target(-1, None).unwrap(), RouteTarget::NoTarget);
        assert_eq!(resolve_target(0, None).unwrap(), RouteTarget::MapEvent(0));
        assert_eq!(resolve_target(8, Some(9)).unwrap(), RouteTarget::MapEvent(8));
    }
    #[test]
    fn follower_range_is_bounded() {
        assert_eq!(resolve_target(-3, None).unwrap(), RouteTarget::Follower(0));
        assert_eq!(resolve_target(-7, None).unwrap(), RouteTarget::Follower(4));
        assert!(resolve_target(-8, None).is_err());
        assert!(resolve_target(i32::MIN, None).is_err());
    }
}
