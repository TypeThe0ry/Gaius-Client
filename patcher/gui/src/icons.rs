use eframe::egui::{self, Color32, Painter, Rect, Response, Sense, Stroke, Ui, Vec2};

/// Small status and navigation marks drawn as geometry instead of text glyphs.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Icon {
    Check,
    Cross,
    Warning,
    Info,
    Ring,
    Bullet,
    Square,
    Diamond,
    Question,
    ChevronRight,
    ChevronDown,
    More,
    Copy,
}

pub fn show(ui: &mut Ui, icon: Icon, color: Color32, size: f32) -> Response {
    let (rect, response) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
    paint(ui.painter(), rect, icon, color);
    response
}

pub fn paint(painter: &Painter, rect: Rect, icon: Icon, color: Color32) {
    let center = rect.center();
    let stroke = Stroke::new((rect.width().min(rect.height()) * 0.11).max(1.0), color);
    let inset = rect.width().min(rect.height()) * 0.2;
    let bounds = rect.shrink(inset);

    match icon {
        Icon::Check => {
            painter.add(egui::Shape::line(
                vec![
                    egui::pos2(bounds.left(), center.y),
                    egui::pos2(center.x - rect.width() * 0.08, bounds.bottom()),
                    egui::pos2(bounds.right(), bounds.top()),
                ],
                stroke,
            ));
        }
        Icon::Cross => {
            painter.line_segment([bounds.left_top(), bounds.right_bottom()], stroke);
            painter.line_segment([bounds.right_top(), bounds.left_bottom()], stroke);
        }
        Icon::Warning => {
            let points = vec![
                egui::pos2(center.x, bounds.top()),
                egui::pos2(bounds.right(), bounds.bottom()),
                egui::pos2(bounds.left(), bounds.bottom()),
            ];
            painter.add(egui::Shape::convex_polygon(
                points,
                color.linear_multiply(0.18),
                stroke,
            ));
            painter.line_segment(
                [
                    egui::pos2(center.x, bounds.top() + rect.height() * 0.22),
                    egui::pos2(center.x, center.y + rect.height() * 0.08),
                ],
                stroke,
            );
            painter.circle_filled(
                egui::pos2(center.x, bounds.bottom() - rect.height() * 0.2),
                stroke.width * 0.75,
                color,
            );
        }
        Icon::Info => {
            painter.circle_stroke(center, bounds.width() * 0.5, stroke);
            painter.line_segment(
                [
                    egui::pos2(center.x, center.y - rect.height() * 0.12),
                    egui::pos2(center.x, center.y + rect.height() * 0.22),
                ],
                stroke,
            );
            painter.circle_filled(
                egui::pos2(center.x, center.y - rect.height() * 0.25),
                stroke.width * 0.75,
                color,
            );
        }
        Icon::Ring => {
            painter.circle_stroke(center, bounds.width() * 0.5, stroke);
        }
        Icon::Bullet => {
            painter.circle_filled(center, bounds.width() * 0.35, color);
        }
        Icon::Square => {
            painter.rect_filled(bounds, 1.5, color);
        }
        Icon::Diamond => {
            painter.add(egui::Shape::convex_polygon(
                vec![
                    egui::pos2(center.x, bounds.top()),
                    egui::pos2(bounds.right(), center.y),
                    egui::pos2(center.x, bounds.bottom()),
                    egui::pos2(bounds.left(), center.y),
                ],
                color.linear_multiply(0.18),
                stroke,
            ));
        }
        Icon::Question => {
            painter.add(egui::Shape::line(
                vec![
                    egui::pos2(
                        center.x - rect.width() * 0.2,
                        center.y - rect.height() * 0.14,
                    ),
                    egui::pos2(
                        center.x - rect.width() * 0.1,
                        center.y - rect.height() * 0.26,
                    ),
                    egui::pos2(
                        center.x + rect.width() * 0.18,
                        center.y - rect.height() * 0.24,
                    ),
                    egui::pos2(
                        center.x + rect.width() * 0.24,
                        center.y - rect.height() * 0.04,
                    ),
                    egui::pos2(center.x, center.y + rect.height() * 0.12),
                    egui::pos2(center.x, center.y + rect.height() * 0.2),
                ],
                stroke,
            ));
            painter.circle_filled(
                egui::pos2(center.x, center.y + rect.height() * 0.34),
                stroke.width * 0.75,
                color,
            );
        }
        Icon::ChevronRight => {
            painter.add(egui::Shape::line(
                vec![
                    egui::pos2(
                        center.x - rect.width() * 0.16,
                        center.y - rect.height() * 0.25,
                    ),
                    center,
                    egui::pos2(
                        center.x - rect.width() * 0.16,
                        center.y + rect.height() * 0.25,
                    ),
                ],
                stroke,
            ));
        }
        Icon::ChevronDown => {
            painter.add(egui::Shape::line(
                vec![
                    egui::pos2(
                        center.x - rect.width() * 0.25,
                        center.y - rect.height() * 0.16,
                    ),
                    center,
                    egui::pos2(
                        center.x + rect.width() * 0.25,
                        center.y - rect.height() * 0.16,
                    ),
                ],
                stroke,
            ));
        }
        Icon::More => {
            let radius = rect.width().min(rect.height()) * 0.08;
            for x in [
                center.x - rect.width() * 0.24,
                center.x,
                center.x + rect.width() * 0.24,
            ] {
                painter.circle_filled(egui::pos2(x, center.y), radius, color);
            }
        }
        Icon::Copy => {
            let back = Rect::from_min_size(
                egui::pos2(
                    rect.left() + rect.width() * 0.2,
                    rect.top() + rect.height() * 0.1,
                ),
                Vec2::new(rect.width() * 0.58, rect.height() * 0.58),
            );
            let front = Rect::from_min_size(
                egui::pos2(
                    rect.left() + rect.width() * 0.12,
                    rect.top() + rect.height() * 0.28,
                ),
                Vec2::new(rect.width() * 0.58, rect.height() * 0.58),
            );
            painter.rect_stroke(back, 1.0, stroke, egui::StrokeKind::Inside);
            painter.rect_stroke(front, 1.0, stroke, egui::StrokeKind::Inside);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_icon_paints_into_a_stable_box() {
        let icons = [
            Icon::Check,
            Icon::Cross,
            Icon::Warning,
            Icon::Info,
            Icon::Ring,
            Icon::Bullet,
            Icon::Square,
            Icon::Diamond,
            Icon::Question,
            Icon::ChevronRight,
            Icon::ChevronDown,
            Icon::More,
            Icon::Copy,
        ];
        egui::__run_test_ui(|ui| {
            for icon in icons {
                show(ui, icon, Color32::WHITE, 20.0);
            }
        });
    }
}
