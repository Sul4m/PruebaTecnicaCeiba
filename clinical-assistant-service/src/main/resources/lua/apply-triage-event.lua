-- Aplica un evento de triaje a la proyeccion de forma atomica.
-- KEYS[1] hash del paciente (nivel, estado, version)
-- KEYS[2] set de eventIds procesados del paciente
-- KEYS[3] hash de conteos por nivel
-- ARGV: eventId, aggregateVersion, nivel, estado
-- Retorna: 0 aplicado, 1 duplicado, 2 version antigua, 3 aplicado con salto de version
local event_id = ARGV[1]
local version = tonumber(ARGV[2])
local nivel = ARGV[3]
local estado = ARGV[4]

if redis.call('SISMEMBER', KEYS[2], event_id) == 1 then
  return 1
end

local prev = redis.call('HMGET', KEYS[1], 'nivel', 'estado', 'version')
local prev_version = tonumber(prev[3])

if prev_version and version <= prev_version then
  return 2
end

-- Ajuste de conteos por diferencia entre el snapshot previo y el nuevo.
if prev_version and prev[2] ~= 'ALTA' then
  redis.call('HINCRBY', KEYS[3], prev[1], -1)
end
if estado ~= 'ALTA' then
  redis.call('HINCRBY', KEYS[3], nivel, 1)
end

redis.call('HSET', KEYS[1], 'nivel', nivel, 'estado', estado, 'version', ARGV[2])
redis.call('SADD', KEYS[2], event_id)

local expected = 1
if prev_version then expected = prev_version + 1 end
if version > expected then
  return 3
end
return 0
