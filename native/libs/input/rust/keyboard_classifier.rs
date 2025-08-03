/*
 * Copyright 2024 The Android Open Source Project
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

//! Contains the KeyboardClassifier, that tries to identify whether an Input device is an
//! alphabetic or non-alphabetic keyboard. It also tracks the KeyEvents produced by the device
//! in order to verify/change the inferred keyboard type.
//!
//! Initial classification:
//! - If DeviceClass includes Dpad, Touch, Cursor, MultiTouch, ExternalStylus, Touchpad, Dpad,
//!   Gamepad, Switch, Joystick, RotaryEncoder => KeyboardType::NonAlphabetic
//! - Otherwise if DeviceClass has Keyboard and not AlphabeticKey => KeyboardType::NonAlphabetic
//! - Otherwise if DeviceClass has both Keyboard and AlphabeticKey => KeyboardType::Alphabetic
//!
//! On process keys:
//! - If KeyboardType::NonAlphabetic and we receive alphabetic key event, then change type to
//!   KeyboardType::Alphabetic. Once changed, no further changes. (i.e. verified = true)
//! - TODO(b/263559234): If KeyboardType::Alphabetic and we don't receive any alphabetic key event
//!    across multiple device connections in a time period, then change type to
//!    KeyboardType::NonAlphabetic. Once changed, it can still change back to Alphabetic
//!    (i.e. verified = false).

use crate::data_store::DataStore;
use crate::input::{DeviceId, InputDevice, KeyboardType};
use crate::keyboard_classification_config::CLASSIFIED_DEVICES;
use crate::{DeviceClass, ModifierState};
use std::collections::HashMap;

/// The KeyboardClassifier is used to classify a keyboard device into non-keyboard, alphabetic
/// keyboard or non-alphabetic keyboard
pub struct KeyboardClassifier {
    device_map: HashMap<DeviceId, KeyboardInfo>,
    data_store: DataStore,
}

struct KeyboardInfo {
    device: InputDevice,
    keyboard_type: KeyboardType,
    is_finalized: bool,
}

impl KeyboardClassifier {
    /// Create a new KeyboardClassifier
    pub fn new(data_store: DataStore) -> Self {
        Self { device_map: HashMap::new(), data_store }
    }

    /// Adds keyboard to KeyboardClassifier
    pub fn notify_keyboard_changed(&mut self, device: InputDevice) {
        let (keyboard_type, is_finalized) = self.classify_keyboard(&device);
        self.device_map
            .insert(device.device_id, KeyboardInfo { device, keyboard_type, is_finalized });
    }

    /// Get keyboard type for a tracked keyboard in KeyboardClassifier
    pub fn get_keyboard_type(&self, device_id: DeviceId) -> KeyboardType {
        if let Some(keyboard) = self.device_map.get(&device_id) {
            keyboard.keyboard_type
        } else {
            KeyboardType::None
        }
    }

    /// Tells if keyboard type classification is finalized. Once finalized the classification can't
    /// change until device is reconnected again.
    ///
    /// Finalized devices are either "alphabetic" keyboards or keyboards in blocklist or
    /// allowlist that are explicitly categorized and won't change with future key events
    pub fn is_finalized(&self, device_id: DeviceId) -> bool {
        if let Some(keyboard) = self.device_map.get(&device_id) {
            keyboard.is_finalized
        } else {
            false
        }
    }

    /// Process a key event and change keyboard type if required.
    /// - If any key event occurs, the keyboard type will change from None to NonAlphabetic
    /// - If an alphabetic key occurs, the keyboard type will change to Alphabetic
    pub fn process_key(
        &mut self,
        device_id: DeviceId,
        evdev_code: i32,
        modifier_state: ModifierState,
    ) {
        if let Some(keyboard) = self.device_map.get_mut(&device_id) {
            // Ignore all key events with modifier state since they can be macro shortcuts used by
            // some non-keyboard peripherals like TV remotes, game controllers, etc.
            if modifier_state.bits() != 0 {
                return;
            }
            if Self::is_alphabetic_key(&evdev_code) {
                keyboard.keyboard_type = KeyboardType::Alphabetic;
                keyboard.is_finalized = true;
                self.data_store.set_keyboard_type(
                    &keyboard.device.identifier.descriptor,
                    keyboard.keyboard_type,
                    keyboard.is_finalized,
                );
            }
        }
    }

    fn classify_keyboard(&mut self, device: &InputDevice) -> (KeyboardType, bool) {
        // This should never happen but having keyboard device class is necessary to be classified
        // as any type of keyboard.
        if !device.classes.contains(DeviceClass::Keyboard) {
            return (KeyboardType::None, true);
        }
        // Normal classification for internal and virtual keyboards
        if !device.classes.contains(DeviceClass::External)
            || device.classes.contains(DeviceClass::Virtual)
        {
            return if device.classes.contains(DeviceClass::AlphabeticKey) {
                (KeyboardType::Alphabetic, true)
            } else {
                (KeyboardType::NonAlphabetic, true)
            };
        }

        // Check in data store
        if let Some((keyboard_type, is_finalized)) =
            self.data_store.get_keyboard_type(&device.identifier.descriptor)
        {
            return (keyboard_type, is_finalized);
        }

        // Check in known device list for classification
        for (vendor, product, keyboard_type, is_finalized) in CLASSIFIED_DEVICES.iter() {
            if device.identifier.vendor == *vendor && device.identifier.product == *product {
                return (*keyboard_type, *is_finalized);
            }
        }

        // Any composite device with multiple device classes should be categorized as non-alphabetic
        // keyboard initially
        if device.classes.contains(DeviceClass::Touch)
            || device.classes.contains(DeviceClass::Cursor)
            || device.classes.contains(DeviceClass::MultiTouch)
            || device.classes.contains(DeviceClass::ExternalStylus)
            || device.classes.contains(DeviceClass::Touchpad)
            || device.classes.contains(DeviceClass::Dpad)
            || device.classes.contains(DeviceClass::Gamepad)
            || device.classes.contains(DeviceClass::Switch)
            || device.classes.contains(DeviceClass::Joystick)
            || device.classes.contains(DeviceClass::RotaryEncoder)
        {
            // If categorized as NonAlphabetic and no device class AlphabeticKey reported by the
            // kernel, we no longer need to process key events to verify.
            return (
                KeyboardType::NonAlphabetic,
                !device.classes.contains(DeviceClass::AlphabeticKey),
            );
        }
        // Only devices with "Keyboard" and "AlphabeticKey" should be classified as full keyboard
        if device.classes.contains(DeviceClass::AlphabeticKey) {
            (KeyboardType::Alphabetic, true)
        } else {
            // If categorized as NonAlphabetic and no device class AlphabeticKey reported by the
            // kernel, we no longer need to process key events to verify.
            (KeyboardType::NonAlphabetic, true)
        }
    }

    fn is_alphabetic_key(evdev_code: &i32) -> bool {
        // Keyboard alphabetic row 1 (Q W E R T Y U I O P [ ])
        (16..=27).contains(evdev_code)
            // Keyboard alphabetic row 2 (A S D F G H J K L ; ' `)
            || (30..=41).contains(evdev_code)
            // Keyboard alphabetic row 3 (\ Z X C V B N M , . /)
            || (43..=53).contains(evdev_code)
    }
}

#[cfg(test)]
mod tests {
    use crate::data_store::{test_file_reader_writer::TestFileReaderWriter, DataStore};
    use crate::input::{DeviceId, InputDevice, KeyboardType};
    use crate::keyboard_classification_config::CLASSIFIED_DEVICES;
    use crate::keyboard_classifier::KeyboardClassifier;
    use crate::{DeviceClass, ModifierState, RustInputDeviceIdentifier};

    static DEVICE_ID: DeviceId = DeviceId(1);
    static SECOND_DEVICE_ID: DeviceId = DeviceId(2);
    static KEY_A: i32 = 30;
    static KEY_1: i32 = 2;

    #[test]
    fn classify_external_alphabetic_keyboard() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard | DeviceClass::AlphabeticKey | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::Alphabetic);
        assert!(classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_external_non_alphabetic_keyboard() {
        let mut classifier = create_classifier();
        classifier
            .notify_keyboard_changed(create_device(DeviceClass::Keyboard | DeviceClass::External));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_mouse_pretending_as_keyboard() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Cursor
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_touchpad_pretending_as_keyboard() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Touchpad
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_stylus_pretending_as_keyboard() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::ExternalStylus
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_dpad_pretending_as_keyboard() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Dpad
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_joystick_pretending_as_keyboard() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Joystick
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_gamepad_pretending_as_keyboard() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Gamepad
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn reclassify_keyboard_on_alphabetic_key_event() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Dpad
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));

        // on alphabetic key event
        classifier.process_key(DEVICE_ID, KEY_A, ModifierState::None);
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::Alphabetic);
        assert!(classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn dont_reclassify_keyboard_on_non_alphabetic_key_event() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Dpad
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));

        // on number key event
        classifier.process_key(DEVICE_ID, KEY_1, ModifierState::None);
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn dont_reclassify_keyboard_on_alphabetic_key_event_with_modifiers() {
        let mut classifier = create_classifier();
        classifier.notify_keyboard_changed(create_device(
            DeviceClass::Keyboard
                | DeviceClass::Dpad
                | DeviceClass::AlphabeticKey
                | DeviceClass::External,
        ));
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));

        classifier.process_key(DEVICE_ID, KEY_A, ModifierState::CtrlOn);
        assert_eq!(classifier.get_keyboard_type(DEVICE_ID), KeyboardType::NonAlphabetic);
        assert!(!classifier.is_finalized(DEVICE_ID));
    }

    #[test]
    fn classify_known_devices() {
        let mut classifier = create_classifier();
        for (vendor, product, keyboard_type, is_finalized) in CLASSIFIED_DEVICES.iter() {
            classifier
                .notify_keyboard_changed(create_device_with_vendor_product_ids(*vendor, *product));
            assert_eq!(classifier.get_keyboard_type(DEVICE_ID), *keyboard_type);
            assert_eq!(classifier.is_finalized(DEVICE_ID), *is_finalized);
        }
    }

    #[test]
    fn classify_previously_reclassified_devices() {
        let test_reader_writer = TestFileReaderWriter::new();
        {
            let mut classifier =
                KeyboardClassifier::new(DataStore::new(Box::new(test_reader_writer.clone())));
            let device = create_device(
                DeviceClass::Keyboard
                    | DeviceClass::Dpad
                    | DeviceClass::AlphabeticKey
                    | DeviceClass::External,
            );
            classifier.notify_keyboard_changed(device);
            classifier.process_key(DEVICE_ID, KEY_A, ModifierState::None);
        }

        // Re-create classifier and data store to mimic a reboot (but use the same file system
        // reader writer)
        {
            let mut classifier =
                KeyboardClassifier::new(DataStore::new(Box::new(test_reader_writer.clone())));
            let device = InputDevice {
                device_id: SECOND_DEVICE_ID,
                identifier: create_identifier(/* vendor= */ 234, /* product= */ 345),
                classes: DeviceClass::Keyboard
                    | DeviceClass::Dpad
                    | DeviceClass::AlphabeticKey
                    | DeviceClass::External,
            };
            classifier.notify_keyboard_changed(device);
            assert_eq!(classifier.get_keyboard_type(SECOND_DEVICE_ID), KeyboardType::Alphabetic);
            assert!(classifier.is_finalized(SECOND_DEVICE_ID));
        }
    }

    fn create_classifier() -> KeyboardClassifier {
        KeyboardClassifier::new(DataStore::new(Box::new(TestFileReaderWriter::new())))
    }

    fn create_identifier(vendor: u16, product: u16) -> RustInputDeviceIdentifier {
        RustInputDeviceIdentifier {
            name: "test_device".to_string(),
            location: "location".to_string(),
            unique_id: "unique_id".to_string(),
            bus: 123,
            vendor,
            product,
            version: 567,
            descriptor: "descriptor".to_string(),
        }
    }

    fn create_device(classes: DeviceClass) -> InputDevice {
        InputDevice {
            device_id: DEVICE_ID,
            identifier: create_identifier(/* vendor= */ 234, /* product= */ 345),
            classes,
        }
    }

    fn create_device_with_vendor_product_ids(vendor: u16, product: u16) -> InputDevice {
        InputDevice {
            device_id: DEVICE_ID,
            identifier: create_identifier(vendor, product),
            classes: DeviceClass::Keyboard | DeviceClass::AlphabeticKey | DeviceClass::External,
        }
    }
}
