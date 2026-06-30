require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'SightsoundtheatresCapacitorMsalAuth'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  s.source = { :git => package['repository']['url'], :tag => "v#{s.version}" }
  s.source_files = 'ios/Sources/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target  = '16.0'
  s.dependency 'Capacitor'
  s.dependency 'MSAL', '~> 2.13'
  s.swift_version = '5.9'
end
