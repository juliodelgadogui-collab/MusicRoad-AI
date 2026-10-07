<?php
return [
    'app_name' => 'Sistema Médico',
    'base_url' => '/medical',
    'db_path' => __DIR__ . '/database/medical.sqlite',
    'storage_path' => __DIR__ . '/storage',
    'app_secret' => getenv('MEDICAL_APP_SECRET') ?: 'ALTERE-ESTE-SEGREDO-EM-PRODUCAO',
    'ai_endpoint' => getenv('MEDICAL_AI_ENDPOINT') ?: '',
    'ai_key' => getenv('MEDICAL_AI_KEY') ?: '',
];
