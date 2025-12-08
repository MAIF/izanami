package fr.maif.izanami.models

import fr.maif.izanami.utils.LowerCaseEnumReader
import pureconfig.generic.derivation.EnumConfigReader

enum IzanamiMode derives LowerCaseEnumReader.EnumConfigReader {
  case Standalone, Leader, Worker
}