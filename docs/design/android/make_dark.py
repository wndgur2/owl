# Generates <Name>Dark.dc.html from each light artboard (M3 baseline light -> dark roles).
import re, sys, os
P = sys.argv[1]
NAMES = ['Main','Entry','EntryPending','EntryMenu','EntryDeleted','Search','Onboarding','Device','DeviceMenu','Settings','Retention']
ROOT_L = 'background: #FFFFFF; color: #1D1B20; font-family'
ROOT_D = 'background: #141218; color: #E6E0E9; font-family'
MAP = {
 '#FFFFFF':'#381E72', '#1D1B20':'#E6E0E9', '#49454F':'#CAC4D0', '#79747E':'#938F99', '#CAC4D0':'#49454F',
 '#6750A4':'#D0BCFF', '#4F378B':'#EADDFF', '#EADDFF':'#4F378B', '#E8DEF8':'#4A4458', '#4A4458':'#E8DEF8',
 '#7D5260':'#EFB8C8', '#F7F2FA':'#1D1B20', '#F3EDF7':'#211F26', '#ECE6F0':'#2B2930', '#E6E0E9':'#36343B',
 '#B3261E':'#F2B8B5', '#F9DEDC':'#8C1D18', '#8C1D18':'#F9DEDC',
 'rgba(29, 27, 32,':'rgba(230, 224, 233,', 'rgba(103, 80, 164,':'rgba(208, 188, 255,', 'rgba(73, 69, 79,':'rgba(202, 196, 208,',
}
pat = re.compile('|'.join(re.escape(k) for k in sorted(MAP, key=len, reverse=True)), re.I)
for n in NAMES:
    s = open(os.path.join(P, n + '.dc.html'), encoding='utf-8').read()
    s = s.replace(ROOT_L, '@@ROOT@@')
    s = pat.sub(lambda m: MAP[m.group(0).upper() if m.group(0).startswith('#') else m.group(0)], s)
    s = s.replace('@@ROOT@@', ROOT_D)
    s = re.sub(r'href="(\w+)\.dc\.html"', r'href="\1Dark.dc.html"', s)
    s = re.sub(r'<dc-import name="(\w+)"', r'<dc-import name="\1Dark"', s)
    s = re.sub(r'<title>(.*?)</title>', r'<title>\1 (다크)</title>', s, count=1)
    open(os.path.join(P, n + 'Dark.dc.html'), 'w', encoding='utf-8').write(s)
    print('wrote', n + 'Dark.dc.html')
