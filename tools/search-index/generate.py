
from pathlib import Path
import json
from pypinyin import lazy_pinyin
r=Path(__file__).resolve().parents[2]
names=set()
for f in (r/'app/src/main/assets/metro').glob('line_beijing_*.json'):
 for s in json.loads(f.read_text(encoding='utf-8'))['stations']:
  names.update([s['name'],*s.get('aliases',[])])
rows=[]
for n in sorted(names):
 syl=lazy_pinyin(n)
 # Known place-name reading rather than generic character readings.
 if n.startswith('什刹海'):syl[:3]=['shi','cha','hai']
 if n.startswith(('长椿街','长阳','长春桥')):syl[0]='chang'
 if n.startswith(('马家堡','十里堡')):syl[2]='pu'
 rows.append(n+'='+ ' '.join(syl))
p=r/'app/src/main/java/com/metronearby/domain/StationPinyin.kt'
p.write_text('''package com.metronearby.domain

/** Generated offline search keys; regenerate with tools/search-index/generate.py.
 * Search transliteration only, not official English station names.
 */
internal object StationPinyin {
    private val entries by lazy {
        data.trimIndent().lineSequence().associate {
            val (name, syllables) = it.split("=", limit = 2)
            name to syllables.split(" ")
        }
    }
    fun keys(name: String): List<String> {
        val syllables = entries[name] ?: return emptyList()
        return listOf(syllables.joinToString(""), syllables.joinToString("") { it.take(1) })
    }
    private val data = """
'''+ '\n'.join(rows)+'''
    """
}
''',encoding='utf-8')
print('Generated names:',len(names))
