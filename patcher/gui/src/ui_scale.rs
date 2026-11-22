use eframe::egui::{self, Vec2};
use serde::{Deserialize, Serialize};

/// App zoom relative to OS scaling, not a replacement for the OS scaling.
#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum UiScale {
    #[default]
    Auto,
    #[serde(rename = "100")]
    P100,
    #[serde(rename = "125")]
    P125,
    #[serde(rename = "150")]
    P150,
    #[serde(rename = "175")]
    P175,
    #[serde(rename = "200")]
    P200,
    #[serde(rename = "250")]
    P250,
}

impl UiScale {
    pub(crate) const VALUES: [Self; 7] = [
        Self::Auto,
        Self::P100,
        Self::P125,
        Self::P150,
        Self::P175,
        Self::P200,
        Self::P250,
    ];

    pub(crate) fn factor(self) -> f32 {
        match self {
            Self::Auto | Self::P100 => 1.0,
            Self::P125 => 1.25,
            Self::P150 => 1.5,
            Self::P175 => 1.75,
            Self::P200 => 2.0,
            Self::P250 => 2.5,
        }
    }

    pub(crate) fn label(self) -> &'static str {
        match self {
            Self::Auto => "scale.auto",
            Self::P100 => "scale.100",
            Self::P125 => "scale.125",
            Self::P150 => "scale.150",
            Self::P175 => "scale.175",
            Self::P200 => "scale.200",
            Self::P250 => "scale.250",
        }
    }

    pub(crate) fn for_factor(factor: f32) -> Option<Self> {
        Self::VALUES
            .into_iter()
            .skip(1)
            .find(|scale| scale.factor() == factor)
    }

    pub(crate) fn step(self, increase: bool) -> Self {
        let index = Self::VALUES
            .iter()
            .position(|&scale| scale == self)
            .unwrap_or(0);
        let index = if increase {
            (index.max(1) + 1).min(6)
        } else {
            index.saturating_sub(1).max(1)
        };
        Self::VALUES[index]
    }
}

/// The compact layout supports a 400×270 effective-point viewport. Keep the
/// requested preference separate so enlargement resumes when the window grows.
pub(crate) fn fit_zoom(requested: f32, native_points: Vec2) -> f32 {
    // Quantize the cap down (for stable font atlases during incremental resizes).
    let cap = (native_points.x / 400.0).min(native_points.y / 270.0);
    let cap = ((cap + 0.0001) * 100.0).floor() / 100.0;
    requested.min(cap.max(1.0))
}

pub(crate) fn configure_input(ctx: &egui::Context) {
    ctx.options_mut(|options| {
        options.zoom_with_keyboard = false;
        options.input_options.zoom_modifier = egui::Modifiers::CTRL;
    });
}

pub(crate) fn input(ctx: &egui::Context, mut choice: UiScale) -> UiScale {
    ctx.input_mut(|input| {
        use egui::{Event, Key, Modifiers};
        if input.consume_key(Modifiers::CTRL, Key::Num0) {
            return UiScale::Auto;
        }
        if input.consume_key(Modifiers::CTRL, Key::Equals)
            || input.consume_key(Modifiers::CTRL, Key::Plus)
        {
            choice = choice.step(true);
        }
        if input.consume_key(Modifiers::CTRL, Key::Minus) {
            choice = choice.step(false);
        }
        // A wheel may send several small deltas in a single frame. Treat them
        // as one step; egui's zoom_modifier keeps them out of normal scrolling.
        let wheel: f32 = input
            .events
            .iter()
            .filter_map(|event| match event {
                Event::MouseWheel {
                    delta, modifiers, ..
                } if modifiers.ctrl && !modifiers.alt && !modifiers.shift => Some(delta.y),
                _ => None,
            })
            .sum();
        if wheel != 0.0 {
            choice = choice.step(wheel > 0.0);
        }
        choice
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keyboard_and_wheel_select_one_persistable_zoom_preference() {
        use egui::{Event, Key, Modifiers, MouseWheelSource, MouseWheelUnit, TouchPhase, vec2};
        let ctx = egui::Context::default();
        configure_input(&ctx);
        assert!(!ctx.options(|options| options.zoom_with_keyboard));
        let key = |key| Event::Key {
            key,
            physical_key: Some(key),
            pressed: true,
            repeat: false,
            modifiers: Modifiers::CTRL,
        };
        let wheel = Event::MouseWheel {
            unit: MouseWheelUnit::Line,
            delta: vec2(0.0, 1.0),
            phase: TouchPhase::Move,
            source: MouseWheelSource::Wheel,
            modifiers: Modifiers::CTRL,
        };
        let mut choice = UiScale::Auto;
        for (event, expected) in [
            (key(Key::Equals), UiScale::P125),
            (wheel, UiScale::P150),
            (key(Key::Minus), UiScale::P125),
            (key(Key::Num0), UiScale::Auto),
        ] {
            let mut output = ctx.run_ui(
                egui::RawInput {
                    events: vec![event],
                    ..Default::default()
                },
                |_| {
                    choice = input(&ctx, choice);
                },
            );
            output.textures_delta.clear();
            assert_eq!(choice, expected);
        }
    }

    #[test]
    fn compact_floor_limits_zoom_not_os_dpi() {
        assert_eq!(fit_zoom(1.5, Vec2::new(800.0, 540.0)), 1.5);
        assert_eq!(fit_zoom(2.5, Vec2::new(800.0, 540.0)), 2.0);
        assert_eq!(fit_zoom(2.5, Vec2::new(1000.0, 675.0)), 2.5);
        assert_eq!(fit_zoom(2.5, Vec2::new(640.0, 432.0)), 1.6);
        assert_eq!(fit_zoom(1.0, Vec2::new(360.0, 230.0)), 1.0);
        assert_eq!(UiScale::Auto.step(true), UiScale::P125);
        assert_eq!(UiScale::P125.step(false), UiScale::P100);
        assert_eq!(UiScale::P250.step(true), UiScale::P250);
    }
}
