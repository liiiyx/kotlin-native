package org.jetbrains.kotlin.backend.konan.llvm

import llvm.*
import org.jetbrains.kotlin.backend.konan.descriptors.TypedIntrinsic
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.name.Name

private enum class IntrinsicType {
    PLUS,
    MINUS,
    TIMES,
    DIV,
    REM,
    INC,
    DEC,
    UNARY_PLUS,
    UNARY_MINUS,
    SHL,
    SHR,
    USHR,
    AND,
    OR,
    XOR,
    INV,
    PRIMITIVE_CAST,
    COMPARE_TO,
    NOT,
    TO_BITS,
    FROM_BITS
}

internal class IntrinsicGenerator(val codegen: CodeGenerator) {

    private val context = codegen.context

    private val integralTypesOrder = arrayOf(int1Type, int8Type, int16Type, int32Type, int64Type)
    private val realTypesOrder = arrayOf(floatType, doubleType)
    private val typesOrder = integralTypesOrder + realTypesOrder

    // Cast all args to one type.
    private fun FunctionGenerationContext.castArgs(
            args: List<LLVMValueRef>,
            destTy: LLVMTypeRef = findUpperType(args.map { it.type })
    ) = args.map { cast(it, destTy) }

    private fun findUpperType(argTypes: List<LLVMTypeRef>) =
            argTypes.maxWith(Comparator(this::compareTypes))!!

    private fun compareTypes(firstTy: LLVMTypeRef, secondTy: LLVMTypeRef) =
            typesOrder.indexOf(firstTy).compareTo(typesOrder.indexOf(secondTy))

    private fun FunctionGenerationContext.cast(value: LLVMValueRef, destTy: LLVMTypeRef): LLVMValueRef {
        if (value.type == destTy) return value

        if (value.type == floatType && destTy == doubleType) {
            return LLVMBuildFPExt(builder, value, destTy, "")!!
        }
        if (value.type == doubleType && destTy == floatType) {
            return LLVMBuildFPTrunc(builder, value, destTy, "")!!
        }
        if (destTy.isFloatingPoint() && !value.type.isFloatingPoint()) {
            return LLVMBuildSIToFP(builder, value, destTy, "")!!
        }
        if (!destTy.isFloatingPoint() && value.type.isFloatingPoint()) {
            return LLVMBuildFPToSI(builder, value, destTy, "")!!
        }
        // Integral types processing.
        val compResult = compareTypes(value.type, destTy)
        return when {
            compResult < 0 -> sext(value, destTy)
            compResult > 0 -> trunc(value, destTy)
            else -> value
        }
    }
    
    private val IrFunction.llvmReturnType: LLVMTypeRef
        get() = LLVMGetReturnType(codegen.getLlvmFunctionType(this))!!

    private fun getIntrinsicType(callSite: IrCall): IntrinsicType {
        val function = callSite.symbol.owner
        val annotation = function.descriptor.annotations.findAnnotation(TypedIntrinsic)!!
        val value = annotation.allValueArguments[Name.identifier("kind")]!!.value as String
        return IntrinsicType.valueOf(value)
    }

    // Assuming that we checked for `TypedInstrinsic` annotation presence.
    fun evaluateCall(callee: IrCall, args: List<LLVMValueRef>,
                     generationContext: FunctionGenerationContext, currentCodeContext: CodeContext
    ): LLVMValueRef = when (getIntrinsicType(callee)) {
            IntrinsicType.PLUS ->           generationContext.emitPlus(callee, args)
            IntrinsicType.MINUS ->          generationContext.emitMinus(callee, args)
            IntrinsicType.TIMES ->          generationContext.emitTimes(callee, args)
            IntrinsicType.DIV ->            generationContext.emitDiv(callee, args, currentCodeContext)
            IntrinsicType.REM ->            generationContext.emitRem(callee, args)
            IntrinsicType.INC ->            generationContext.emitInc(callee, args)
            IntrinsicType.DEC ->            generationContext.emitDec(callee, args)
            IntrinsicType.UNARY_PLUS ->     generationContext.emitUnaryPlus(callee, args)
            IntrinsicType.UNARY_MINUS ->    generationContext.emitUnaryMinus(callee, args)
            IntrinsicType.SHL ->            generationContext.emitShl(callee, args)
            IntrinsicType.SHR ->            generationContext.emitShr(callee, args)
            IntrinsicType.USHR ->           generationContext.emitUshr(callee, args)
            IntrinsicType.AND ->            generationContext.emitAnd(callee, args)
            IntrinsicType.OR ->             generationContext.emitOr(callee, args)
            IntrinsicType.XOR ->            generationContext.emitXor(callee, args)
            IntrinsicType.INV ->            generationContext.emitInv(callee, args)
            IntrinsicType.COMPARE_TO ->     generationContext.emitCompareTo(callee, args)
            IntrinsicType.PRIMITIVE_CAST -> generationContext.emitPrimitiveCast(callee, args)
            IntrinsicType.NOT ->            generationContext.emitNot(callee, args)
            IntrinsicType.FROM_BITS ->      generationContext.emitReinterpret(callee, args)
            IntrinsicType.TO_BITS ->        generationContext.emitReinterpret(callee, args)
        }

    private fun FunctionGenerationContext.emitReinterpret(callee: IrCall, args: List<LLVMValueRef>) =
            bitcast(callee.symbol.owner.llvmReturnType, args[0])

    private fun FunctionGenerationContext.emitNot(callee: IrCall, args: List<LLVMValueRef>) =
            not(args[0])
    
    private fun FunctionGenerationContext.emitPlus(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFAdd(builder, first, second, "")
        } else {
            LLVMBuildAdd(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitPrimitiveCast(callee: IrCall, args: List<LLVMValueRef>) =
            cast(args[0], callee.symbol.owner.llvmReturnType)

    private fun FunctionGenerationContext.emitShift(callee: IrCall, args: List<LLVMValueRef>): Pair<LLVMValueRef, LLVMValueRef> {
        val first = args[0]
        val second = args[1]
        val shift = if (first.type == int64Type) {
            val tmp = LLVMBuildAnd(builder, second, Int32(63).llvm, "")
            LLVMBuildZExt(builder, tmp, int64Type, "")
        } else {
            LLVMBuildAnd(builder, second, Int32(31).llvm, "")
        }!!
        return Pair(first, shift)
    }

    private fun FunctionGenerationContext.emitShl(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val (first, shift) = emitShift(callee, args)
        return LLVMBuildShl(builder, first, shift, "")!!
    }

    private fun FunctionGenerationContext.emitShr(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val (first, shift) = emitShift(callee, args)
        return LLVMBuildAShr(builder, first, shift, "")!!
    }

    private fun FunctionGenerationContext.emitUshr(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val (first, shift) = emitShift(callee, args)
        return LLVMBuildLShr(builder, first, shift, "")!!
    }

    private fun FunctionGenerationContext.emitAnd(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return and(first, second)
    }

    private fun FunctionGenerationContext.emitOr(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return or(first, second)
    }

    private fun FunctionGenerationContext.emitXor(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return xor(first, second)
    }

    private fun FunctionGenerationContext.emitInv(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val mask = makeConstOfType(first.type, -1)
        return xor(first, mask)
    }

    private fun FunctionGenerationContext.emitMinus(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef  {
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFSub(builder, first, second, "")
        } else {
            LLVMBuildSub(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitTimes(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFMul(builder, first, second, "")
        } else {
            LLVMBuildMul(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitDiv(callee: IrCall, args: List<LLVMValueRef>, currentCodeContext: CodeContext): LLVMValueRef {
        val divider = args[1]
        if (!divider.type.isFloatingPoint()) {
            ifThen(icmpEq(divider, Zero(divider.type).llvm)) {
                val throwArithExc = codegen.llvmFunction(context.ir.symbols.throwArithmeticException.owner)
                call(throwArithExc, emptyList(), Lifetime.GLOBAL, exceptionHandler = currentCodeContext.exceptionHandler)
                unreachable()
            }
        }
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFDiv(builder, first, second, "")
        } else {
            LLVMBuildSDiv(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitRem(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val function = callee.symbol.owner
        val (first, second) = castArgs(args, function.llvmReturnType)
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFRem(builder, first, second, "")
        } else {
            LLVMBuildSRem(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitInc(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val const1 = makeConstOfType(first.type, 1)
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFAdd(builder, first, const1, "")
        } else {
            LLVMBuildAdd(builder, first, const1, "")
        }!!
    }

    private fun FunctionGenerationContext.emitDec(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val const1 = makeConstOfType(first.type, 1)
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFSub(builder, first, const1, "")
        } else {
            LLVMBuildSub(builder, first, const1, "")
        }!!
    }

    private fun FunctionGenerationContext.emitUnaryPlus(callee: IrCall, args: List<LLVMValueRef>) =
            cast(args[0], callee.symbol.owner.llvmReturnType)

    private fun FunctionGenerationContext.emitUnaryMinus(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val destTy = callee.symbol.owner.llvmReturnType
        val first = cast(args[0], destTy)
        val const0 = makeConstOfType(destTy, 0)
        return if (destTy.isFloatingPoint()) {
            LLVMBuildFSub(builder, const0, first, "")
        } else {
            LLVMBuildSub(builder, const0, first, "")
        }!!
    }

    private fun FunctionGenerationContext.emitCompareTo(callee: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = castArgs(args)
        val equal: LLVMValueRef
        val less: LLVMValueRef
        if (first.type.isFloatingPoint()) {
            equal = fcmpEq(first, second)
            less = fcmpLt(first, second)
        } else {
            equal = icmpEq(first, second)
            less = icmpLt(first, second)
        }
        val tmp = LLVMBuildSelect(builder, less, Int32(-1).llvm, Int32(1).llvm, "")
        return LLVMBuildSelect(builder, equal, Int32(0).llvm, tmp, "")!!
    }

    private fun makeConstOfType(type: LLVMTypeRef, value: Int): LLVMValueRef = when (type) {
        int8Type -> Int8(value.toByte()).llvm
        int16Type -> Char16(value.toChar()).llvm
        int32Type -> Int32(value).llvm
        int64Type -> Int64(value.toLong()).llvm
        floatType -> Float32(value.toFloat()).llvm
        doubleType -> Float64(value.toDouble()).llvm
        else -> context.reportCompilationError("Unexpected primitive type: $type")
    }
}