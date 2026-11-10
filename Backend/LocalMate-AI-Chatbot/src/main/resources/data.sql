INSERT INTO app_settings (setting_key, setting_value)
VALUES ('system_prompt', 'You are a helpful, concise AI assistant. Give accurate and practical answers.')
ON DUPLICATE KEY UPDATE setting_key = setting_key;
