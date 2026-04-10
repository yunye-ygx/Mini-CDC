if redis.call('EXISTS', KEYS[1]) == 1 then
  return 'DUPLICATE'
end
for i = 2, #KEYS do
  redis.call('SET', KEYS[i], ARGV[i - 1])
end
redis.call('SET', KEYS[1], ARGV[#ARGV])
return 'APPLIED'
