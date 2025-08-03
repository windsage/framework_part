/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! Sticky keys input filter implementation.
//! Sticky keys is an accessibility feature that assists users who have physical disabilities or
//! helps users reduce repetitive strain injury. It serializes keystrokes instead of pressing
//! multiple keys at a time, allowing the user to press and release a modifier key, such as Shift,
//! Ctrl, Alt, or any other modifier key, and have it remain active until any other key is pressed.
use crate::input_filter::{Filter, ModifierStateListener};
use com_android_server_inputflinger::aidl::com::android::server::inputflinger::{
    DeviceInfo::DeviceInfo, KeyEvent::KeyEvent, KeyEventAction::KeyEventAction,
};
use input::ModifierState;
use std::any::Any;
use std::collections::{HashMap, HashSet};

// Modifier keycodes: values are from /frameworks/native/include/android/keycodes.h
const KEYCODE_ALT_LEFT: i32 = 57;
const KEYCODE_ALT_RIGHT: i32 = 58;
const KEYCODE_SHIFT_LEFT: i32 = 59;
const KEYCODE_SHIFT_RIGHT: i32 = 60;
const KEYCODE_SYM: i32 = 63;
const KEYCODE_CTRL_LEFT: i32 = 113;
const KEYCODE_CTRL_RIGHT: i32 = 114;
const KEYCODE_CAPS_LOCK: i32 = 115;
const KEYCODE_SCROLL_LOCK: i32 = 116;
const KEYCODE_META_LEFT: i32 = 117;
const KEYCODE_META_RIGHT: i32 = 118;
const KEYCODE_FUNCTION: i32 = 119;
const KEYCODE_NUM_LOCK: i32 = 143;
static STICKY_KEYS_DATA: &str = "sticky_keys_data";

pub struct StickyKeysFilter {
    next: Box<dyn Filter + Send + Sync>,
    listener: ModifierStateListener,
    data: Data,
    down_key_map: HashMap<i32, HashSet<i32>>,
}

#[derive(Default)]
/// Data that will be saved and restored across configuration changes
struct Data {
    /// Tracking devices that contributed to the modifier state.
    contributing_devices: HashSet<i32>,
    /// State describing the current enabled modifiers. This contain both locked and non-locked
    /// modifier state bits.
    modifier_state: ModifierState,
    /// State describing the current locked modifiers. These modifiers will not be cleared on a
    /// non-modifier key press. They will be cleared only if the locked modifier key is pressed
    /// again.
    locked_modifier_state: ModifierState,
}

impl StickyKeysFilter {
    /// Create a new StickyKeysFilter instance.
    pub fn new(
        next: Box<dyn Filter + Send + Sync>,
        listener: ModifierStateListener,
    ) -> StickyKeysFilter {
        Self { next, listener, data: Default::default(), down_key_map: HashMap::new() }
    }
}

impl Filter for StickyKeysFilter {
    fn notify_key(&mut self, event: &KeyEvent) {
        let down = event.action == KeyEventAction::DOWN;
        let up = event.action == KeyEventAction::UP;
        let mut modifier_state = self.data.modifier_state;
        let mut locked_modifier_state = self.data.locked_modifier_state;
        if down {
            let down_keys = self.down_key_map.entry(event.deviceId).or_default();
            down_keys.insert(event.keyCode);
        } else {
            if !self.down_key_map.contains_key(&event.deviceId) {
                self.next.notify_key(event);
                return;
            }
            let down_keys = self.down_key_map.get_mut(&event.deviceId).unwrap();
            if !down_keys.contains(&event.keyCode) {
                self.next.notify_key(event);
                return;
            }
            down_keys.remove(&event.keyCode);
            if down_keys.is_empty() {
                self.down_key_map.remove(&event.deviceId);
            }
        }
        if !is_ephemeral_modifier_key(event.keyCode) {
            // If non-ephemeral modifier key (i.e. non-modifier keys + toggle modifier keys like
            // CAPS_LOCK, NUM_LOCK etc.), don't block key and pass in the sticky modifier state with
            // the KeyEvent.
            let old_modifier_state = ModifierState::from_bits(event.metaState as u32).unwrap();
            let mut new_event = *event;
            // Send the current modifier state with the key event before clearing non-locked
            // modifier state
            new_event.metaState =
                (clear_ephemeral_modifier_state(old_modifier_state) | modifier_state).bits() as i32;
            self.next.notify_key(&new_event);
            if up && !is_modifier_key(event.keyCode) {
                modifier_state =
                    clear_ephemeral_modifier_state(modifier_state) | locked_modifier_state;
            }
        } else if up {
            // Update contributing devices to track keyboards
            self.data.contributing_devices.insert(event.deviceId);
            // If ephemeral modifier key, capture the key and update the sticky modifier states
            let modifier_key_mask = get_ephemeral_modifier_key_mask(event.keyCode);
            let symmetrical_modifier_key_mask = get_symmetrical_modifier_key_mask(event.keyCode);
            if locked_modifier_state & modifier_key_mask != ModifierState::None {
                locked_modifier_state &= !symmetrical_modifier_key_mask;
                modifier_state &= !symmetrical_modifier_key_mask;
            } else if modifier_key_mask & modifier_state != ModifierState::None {
                locked_modifier_state |= modifier_key_mask;
                modifier_state =
                    (modifier_state & !symmetrical_modifier_key_mask) | modifier_key_mask;
            } else {
                modifier_state |= modifier_key_mask;
            }
        }
        if self.data.modifier_state != modifier_state
            || self.data.locked_modifier_state != locked_modifier_state
        {
            self.data.modifier_state = modifier_state;
            self.data.locked_modifier_state = locked_modifier_state;
            self.listener.modifier_state_changed(modifier_state, locked_modifier_state);
        }
    }

    fn notify_devices_changed(&mut self, device_infos: &[DeviceInfo]) {
        // Clear state if all contributing devices removed
        self.data.contributing_devices.retain(|id| device_infos.iter().any(|x| *id == x.deviceId));
        if self.data.contributing_devices.is_empty()
            && (self.data.modifier_state != ModifierState::None
                || self.data.locked_modifier_state != ModifierState::None)
        {
            self.data.modifier_state = ModifierState::None;
            self.data.locked_modifier_state = ModifierState::None;
            self.listener.modifier_state_changed(ModifierState::None, ModifierState::None);
        }
        self.down_key_map.retain(|key, _| device_infos.iter().any(|x| *key == x.deviceId));
        self.next.notify_devices_changed(device_infos);
    }

    fn save(
        &mut self,
        mut state: HashMap<&'static str, Box<dyn Any + Send + Sync>>,
    ) -> HashMap<&'static str, Box<dyn Any + Send + Sync>> {
        let data = Data {
            contributing_devices: self.data.contributing_devices.clone(),
            modifier_state: self.data.modifier_state,
            locked_modifier_state: self.data.locked_modifier_state,
        };
        state.insert(STICKY_KEYS_DATA, Box::new(data));
        self.next.save(state)
    }

    fn restore(&mut self, state: &HashMap<&'static str, Box<dyn Any + Send + Sync>>) {
        if let Some(value) = state.get(STICKY_KEYS_DATA) {
            if let Some(data) = value.downcast_ref::<Data>() {
                self.data.contributing_devices = data.contributing_devices.clone();
                self.data.modifier_state = data.modifier_state;
                self.data.locked_modifier_state = data.locked_modifier_state;
            }
        }
        self.next.restore(state)
    }

    fn destroy(&mut self) {
        self.next.destroy();
    }

    fn dump(&mut self, dump_str: String) -> String {
        let mut result = "Sticky Keys filter: \n".to_string();
        result += &format!("\tmodifier_state = {:?}\n", self.data.modifier_state);
        result += &format!("\tlocked_modifier_state = {:?}\n", self.data.locked_modifier_state);
        result += &format!("\tcontributing_devices = {:?}\n", self.data.contributing_devices);
        result += &format!("\tdown_key_map = {:?}\n", self.down_key_map);
        self.next.dump(dump_str + &result)
    }
}

fn is_modifier_key(keycode: i32) -> bool {
    matches!(
        keycode,
        KEYCODE_ALT_LEFT
            | KEYCODE_ALT_RIGHT
            | KEYCODE_SHIFT_LEFT
            | KEYCODE_SHIFT_RIGHT
            | KEYCODE_CTRL_LEFT
            | KEYCODE_CTRL_RIGHT
            | KEYCODE_META_LEFT
            | KEYCODE_META_RIGHT
            | KEYCODE_SYM
            | KEYCODE_FUNCTION
            | KEYCODE_CAPS_LOCK
            | KEYCODE_NUM_LOCK
            | KEYCODE_SCROLL_LOCK
    )
}

fn is_ephemeral_modifier_key(keycode: i32) -> bool {
    matches!(
        keycode,
        KEYCODE_ALT_LEFT
            | KEYCODE_ALT_RIGHT
            | KEYCODE_SHIFT_LEFT
            | KEYCODE_SHIFT_RIGHT
            | KEYCODE_CTRL_LEFT
            | KEYCODE_CTRL_RIGHT
            | KEYCODE_META_LEFT
            | KEYCODE_META_RIGHT
    )
}

fn get_ephemeral_modifier_key_mask(keycode: i32) -> ModifierState {
    match keycode {
        KEYCODE_ALT_LEFT => ModifierState::AltLeftOn | ModifierState::AltOn,
        KEYCODE_ALT_RIGHT => ModifierState::AltRightOn | ModifierState::AltOn,
        KEYCODE_SHIFT_LEFT => ModifierState::ShiftLeftOn | ModifierState::ShiftOn,
        KEYCODE_SHIFT_RIGHT => ModifierState::ShiftRightOn | ModifierState::ShiftOn,
        KEYCODE_CTRL_LEFT => ModifierState::CtrlLeftOn | ModifierState::CtrlOn,
        KEYCODE_CTRL_RIGHT => ModifierState::CtrlRightOn | ModifierState::CtrlOn,
        KEYCODE_META_LEFT => ModifierState::MetaLeftOn | ModifierState::MetaOn,
        KEYCODE_META_RIGHT => ModifierState::MetaRightOn | ModifierState::MetaOn,
        _ => ModifierState::None,
    }
}

/// Modifier mask including both left and right versions of a modifier key.
fn get_symmetrical_modifier_key_mask(keycode: i32) -> ModifierState {
    match keycode {
        KEYCODE_ALT_LEFT | KEYCODE_ALT_RIGHT => {
            ModifierState::AltLeftOn | ModifierState::AltRightOn | ModifierState::AltOn
        }
        KEYCODE_SHIFT_LEFT | KEYCODE_SHIFT_RIGHT => {
            ModifierState::ShiftLeftOn | ModifierState::ShiftRightOn | ModifierState::ShiftOn
        }
        KEYCODE_CTRL_LEFT | KEYCODE_CTRL_RIGHT => {
            ModifierState::CtrlLeftOn | ModifierState::CtrlRightOn | ModifierState::CtrlOn
        }
        KEYCODE_META_LEFT | KEYCODE_META_RIGHT => {
            ModifierState::MetaLeftOn | ModifierState::MetaRightOn | ModifierState::MetaOn
        }
        _ => ModifierState::None,
    }
}

fn clear_ephemeral_modifier_state(modifier_state: ModifierState) -> ModifierState {
    modifier_state
        & !(ModifierState::AltLeftOn
            | ModifierState::AltRightOn
            | ModifierState::AltOn
            | ModifierState::ShiftLeftOn
            | ModifierState::ShiftRightOn
            | ModifierState::ShiftOn
            | ModifierState::CtrlLeftOn
            | ModifierState::CtrlRightOn
            | ModifierState::CtrlOn
            | ModifierState::MetaLeftOn
            | ModifierState::MetaRightOn
            | ModifierState::MetaOn)
}

#[cfg(test)]
mod tests {
    use crate::input_filter::{
        test_callbacks::TestCallbacks, test_filter::TestFilter, Filter, ModifierStateListener,
    };
    use crate::sticky_keys_filter::{
        StickyKeysFilter, KEYCODE_ALT_LEFT, KEYCODE_ALT_RIGHT, KEYCODE_CAPS_LOCK,
        KEYCODE_CTRL_LEFT, KEYCODE_CTRL_RIGHT, KEYCODE_FUNCTION, KEYCODE_META_LEFT,
        KEYCODE_META_RIGHT, KEYCODE_NUM_LOCK, KEYCODE_SCROLL_LOCK, KEYCODE_SHIFT_LEFT,
        KEYCODE_SHIFT_RIGHT, KEYCODE_SYM,
    };
    use android_hardware_input_common::aidl::android::hardware::input::common::Source::Source;
    use binder::Strong;
    use com_android_server_inputflinger::aidl::com::android::server::inputflinger::{
        DeviceInfo::DeviceInfo, IInputFilter::IInputFilterCallbacks::IInputFilterCallbacks,
        KeyEvent::KeyEvent, KeyEventAction::KeyEventAction,
    };
    use input::KeyboardType;
    use input::ModifierState;
    use std::collections::HashMap;
    use std::sync::{Arc, RwLock};

    static DEVICE_ID: i32 = 1;
    static KEY_A: i32 = 29;
    static BASE_KEY_DOWN: KeyEvent = KeyEvent {
        id: 1,
        deviceId: DEVICE_ID,
        downTime: 0,
        readTime: 0,
        eventTime: 0,
        source: Source::KEYBOARD,
        displayId: 0,
        policyFlags: 0,
        action: KeyEventAction::DOWN,
        flags: 0,
        keyCode: 0,
        scanCode: 0,
        metaState: 0,
    };

    static BASE_KEY_UP: KeyEvent = KeyEvent { action: KeyEventAction::UP, ..BASE_KEY_DOWN };

    #[test]
    fn test_notify_key_consumes_ephemeral_modifier_keys() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        let key_codes = &[
            KEYCODE_ALT_LEFT,
            KEYCODE_ALT_RIGHT,
            KEYCODE_CTRL_LEFT,
            KEYCODE_CTRL_RIGHT,
            KEYCODE_SHIFT_LEFT,
            KEYCODE_SHIFT_RIGHT,
            KEYCODE_META_LEFT,
            KEYCODE_META_RIGHT,
        ];
        for key_code in key_codes.iter() {
            sticky_keys_filter.notify_key(&KeyEvent { keyCode: *key_code, ..BASE_KEY_DOWN });
            assert!(test_filter.last_event().is_none());

            sticky_keys_filter.notify_key(&KeyEvent { keyCode: *key_code, ..BASE_KEY_UP });
            assert!(test_filter.last_event().is_none());
        }
    }

    #[test]
    fn test_notify_key_passes_ephemeral_modifier_keys_if_only_key_up_occurs() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        let key_codes = &[
            KEYCODE_ALT_LEFT,
            KEYCODE_ALT_RIGHT,
            KEYCODE_CTRL_LEFT,
            KEYCODE_CTRL_RIGHT,
            KEYCODE_SHIFT_LEFT,
            KEYCODE_SHIFT_RIGHT,
            KEYCODE_META_LEFT,
            KEYCODE_META_RIGHT,
        ];
        for key_code in key_codes.iter() {
            let event = KeyEvent { keyCode: *key_code, ..BASE_KEY_UP };
            sticky_keys_filter.notify_key(&event);
            assert_eq!(test_filter.last_event().unwrap(), event);
        }
    }

    #[test]
    fn test_notify_key_passes_non_ephemeral_modifier_keys() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        let key_codes = &[
            KEYCODE_CAPS_LOCK,
            KEYCODE_NUM_LOCK,
            KEYCODE_SCROLL_LOCK,
            KEYCODE_FUNCTION,
            KEYCODE_SYM,
        ];
        for key_code in key_codes.iter() {
            let event = KeyEvent { keyCode: *key_code, ..BASE_KEY_DOWN };
            sticky_keys_filter.notify_key(&event);
            assert_eq!(test_filter.last_event().unwrap(), event);
            let event = KeyEvent { keyCode: *key_code, ..BASE_KEY_UP };
            sticky_keys_filter.notify_key(&event);
            assert_eq!(test_filter.last_event().unwrap(), event);
        }
    }

    #[test]
    fn test_notify_key_passes_non_modifier_keys() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        let event = KeyEvent { keyCode: KEY_A, ..BASE_KEY_DOWN };
        sticky_keys_filter.notify_key(&event);
        assert_eq!(test_filter.last_event().unwrap(), event);

        let event = KeyEvent { keyCode: KEY_A, ..BASE_KEY_UP };
        sticky_keys_filter.notify_key(&event);
        assert_eq!(test_filter.last_event().unwrap(), event);
    }

    #[test]
    fn test_modifier_state_updated_on_modifier_key_press() {
        let mut test_filter = TestFilter::new();
        let mut test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        let test_states = &[
            (KEYCODE_ALT_LEFT, ModifierState::AltOn | ModifierState::AltLeftOn),
            (KEYCODE_ALT_RIGHT, ModifierState::AltOn | ModifierState::AltRightOn),
            (KEYCODE_CTRL_LEFT, ModifierState::CtrlOn | ModifierState::CtrlLeftOn),
            (KEYCODE_CTRL_RIGHT, ModifierState::CtrlOn | ModifierState::CtrlRightOn),
            (KEYCODE_SHIFT_LEFT, ModifierState::ShiftOn | ModifierState::ShiftLeftOn),
            (KEYCODE_SHIFT_RIGHT, ModifierState::ShiftOn | ModifierState::ShiftRightOn),
            (KEYCODE_META_LEFT, ModifierState::MetaOn | ModifierState::MetaLeftOn),
            (KEYCODE_META_RIGHT, ModifierState::MetaOn | ModifierState::MetaRightOn),
        ];
        for test_state in test_states.iter() {
            test_filter.clear();
            test_callbacks.clear();
            sticky_keys_filter.notify_key(&KeyEvent { keyCode: test_state.0, ..BASE_KEY_DOWN });
            assert_eq!(test_callbacks.get_last_modifier_state(), ModifierState::None);
            assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);

            sticky_keys_filter.notify_key(&KeyEvent { keyCode: test_state.0, ..BASE_KEY_UP });
            assert_eq!(test_callbacks.get_last_modifier_state(), test_state.1);
            assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);

            // Re-send keys to lock it
            sticky_keys_filter.notify_key(&KeyEvent { keyCode: test_state.0, ..BASE_KEY_DOWN });
            assert_eq!(test_callbacks.get_last_modifier_state(), test_state.1);
            assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);

            sticky_keys_filter.notify_key(&KeyEvent { keyCode: test_state.0, ..BASE_KEY_UP });
            assert_eq!(test_callbacks.get_last_modifier_state(), test_state.1);
            assert_eq!(test_callbacks.get_last_locked_modifier_state(), test_state.1);

            // Re-send keys to clear
            sticky_keys_filter.notify_key(&KeyEvent { keyCode: test_state.0, ..BASE_KEY_DOWN });
            assert_eq!(test_callbacks.get_last_modifier_state(), test_state.1);
            assert_eq!(test_callbacks.get_last_locked_modifier_state(), test_state.1);

            sticky_keys_filter.notify_key(&KeyEvent { keyCode: test_state.0, ..BASE_KEY_UP });
            assert_eq!(test_callbacks.get_last_modifier_state(), ModifierState::None);
            assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);
        }
    }

    #[test]
    fn test_modifier_state_cleared_on_non_modifier_key_press() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_UP });

        assert_eq!(
            test_callbacks.get_last_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
        assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEY_A, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEY_A, ..BASE_KEY_UP });

        assert_eq!(test_callbacks.get_last_modifier_state(), ModifierState::None);
        assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);
    }

    #[test]
    fn test_modifier_state_unchanged_on_non_modifier_key_up_without_down() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_UP });

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEY_A, ..BASE_KEY_UP });

        assert_eq!(
            test_callbacks.get_last_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
        assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);
    }

    #[test]
    fn test_locked_modifier_state_not_cleared_on_non_modifier_key_press() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_UP });

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_UP });

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_SHIFT_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_SHIFT_LEFT, ..BASE_KEY_UP });

        assert_eq!(
            test_callbacks.get_last_modifier_state(),
            ModifierState::ShiftLeftOn
                | ModifierState::ShiftOn
                | ModifierState::CtrlLeftOn
                | ModifierState::CtrlOn
        );
        assert_eq!(
            test_callbacks.get_last_locked_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEY_A, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEY_A, ..BASE_KEY_UP });

        assert_eq!(
            test_callbacks.get_last_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
        assert_eq!(
            test_callbacks.get_last_locked_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
    }

    #[test]
    fn test_modifier_state_restored_on_recreation() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_UP });

        let saved_state = sticky_keys_filter.save(HashMap::new());
        sticky_keys_filter.destroy();

        // Create a new Sticky keys filter
        let test_filter = TestFilter::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        sticky_keys_filter.restore(&saved_state);
        assert_eq!(
            test_callbacks.get_last_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
        assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_UP });
        assert_eq!(
            test_callbacks.get_last_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
        assert_eq!(
            test_callbacks.get_last_locked_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
    }

    #[test]
    fn test_key_events_have_sticky_modifier_state() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_DOWN });
        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEYCODE_CTRL_LEFT, ..BASE_KEY_UP });

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEY_A, ..BASE_KEY_DOWN });
        assert_eq!(
            test_filter.last_event().unwrap().metaState as u32,
            (ModifierState::CtrlLeftOn | ModifierState::CtrlOn).bits()
        );

        sticky_keys_filter.notify_key(&KeyEvent { keyCode: KEY_A, ..BASE_KEY_UP });
        assert_eq!(
            test_filter.last_event().unwrap().metaState as u32,
            (ModifierState::CtrlLeftOn | ModifierState::CtrlOn).bits()
        );
    }

    #[test]
    fn test_modifier_state_not_cleared_until_all_devices_removed() {
        let test_filter = TestFilter::new();
        let test_callbacks = TestCallbacks::new();
        let mut sticky_keys_filter = setup_filter(
            Box::new(test_filter.clone()),
            Arc::new(RwLock::new(Strong::new(Box::new(test_callbacks.clone())))),
        );
        sticky_keys_filter.notify_key(&KeyEvent {
            deviceId: 1,
            keyCode: KEYCODE_CTRL_LEFT,
            ..BASE_KEY_DOWN
        });
        sticky_keys_filter.notify_key(&KeyEvent {
            deviceId: 1,
            keyCode: KEYCODE_CTRL_LEFT,
            ..BASE_KEY_UP
        });

        sticky_keys_filter.notify_key(&KeyEvent {
            deviceId: 2,
            keyCode: KEYCODE_CTRL_LEFT,
            ..BASE_KEY_DOWN
        });
        sticky_keys_filter.notify_key(&KeyEvent {
            deviceId: 2,
            keyCode: KEYCODE_CTRL_LEFT,
            ..BASE_KEY_UP
        });

        sticky_keys_filter.notify_devices_changed(&[DeviceInfo {
            deviceId: 2,
            external: true,
            keyboardType: KeyboardType::Alphabetic as i32,
        }]);
        assert_eq!(
            test_callbacks.get_last_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );
        assert_eq!(
            test_callbacks.get_last_locked_modifier_state(),
            ModifierState::CtrlLeftOn | ModifierState::CtrlOn
        );

        sticky_keys_filter.notify_devices_changed(&[]);
        assert_eq!(test_callbacks.get_last_modifier_state(), ModifierState::None);
        assert_eq!(test_callbacks.get_last_locked_modifier_state(), ModifierState::None);
    }

    fn setup_filter(
        next: Box<dyn Filter + Send + Sync>,
        callbacks: Arc<RwLock<Strong<dyn IInputFilterCallbacks>>>,
    ) -> StickyKeysFilter {
        StickyKeysFilter::new(next, ModifierStateListener::new(callbacks))
    }
}
