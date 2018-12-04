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
    SIGNED_DIV,
    SIGNED_REM,
    UNSIGNED_DIV,
    UNSIGNED_REM,
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
    SIGNED_CAST,
    UNSIGNED_CAST,
    SIGNED_COMPARE_TO,
    UNSIGNED_COMPARE_TO,
    NOT,
    TO_BITS,
    FROM_BITS
}

internal class IntrinsicGenerator(val codegen: CodeGenerator) {

    private val context = codegen.context

    private fun compareTypesByWidth(a: LLVMTypeRef, b: LLVMTypeRef) =
            LLVMGetIntTypeWidth(a).compareTo(LLVMGetIntTypeWidth(b))

    private fun FunctionGenerationContext.cast(value: LLVMValueRef, destTy: LLVMTypeRef, signed: Boolean): LLVMValueRef {
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
        val compResult = compareTypesByWidth(value.type, destTy)
        return when {
            compResult < 0 -> ext(value, destTy, signed)
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

    fun evaluateCall(callSite: IrCall, args: List<LLVMValueRef>, generationContext: FunctionGenerationContext, exceptionHandler: ExceptionHandler): LLVMValueRef =
            generationContext.evaluateCall(callSite, args, exceptionHandler)

    // Assuming that we checked for `TypedIntrinsic` annotation presence.
    private fun FunctionGenerationContext.evaluateCall(callSite: IrCall, args: List<LLVMValueRef>, exceptionHandler: ExceptionHandler): LLVMValueRef {
        val callee = callSite.symbol.owner
        val result = when (getIntrinsicType(callSite)) {
            IntrinsicType.PLUS -> emitPlus(args)
            IntrinsicType.MINUS -> emitMinus(args)
            IntrinsicType.TIMES -> emitTimes(args)
            IntrinsicType.SIGNED_DIV -> emitSignedDiv(args, exceptionHandler)
            IntrinsicType.SIGNED_REM -> emitSignedRem(args, exceptionHandler)
            IntrinsicType.UNSIGNED_DIV -> emitUnsignedDiv(args, exceptionHandler)
            IntrinsicType.UNSIGNED_REM -> emitUnsignedRem(args, exceptionHandler)
            IntrinsicType.INC -> emitInc(args)
            IntrinsicType.DEC -> emitDec(args)
            IntrinsicType.UNARY_PLUS -> emitUnaryPlus(args)
            IntrinsicType.UNARY_MINUS -> emitUnaryMinus(args)
            IntrinsicType.SHL -> emitShl(args)
            IntrinsicType.SHR -> emitShr(args)
            IntrinsicType.USHR -> emitUshr(args)
            IntrinsicType.AND -> emitAnd(args)
            IntrinsicType.OR -> emitOr(args)
            IntrinsicType.XOR -> emitXor(args)
            IntrinsicType.INV -> emitInv(args)
            IntrinsicType.SIGNED_COMPARE_TO -> emitSignedCompareTo(args)
            IntrinsicType.UNSIGNED_COMPARE_TO -> emitUnsignedCompareTo(args)
            IntrinsicType.SIGNED_CAST -> emitSignedCast(callSite, args)
            IntrinsicType.UNSIGNED_CAST -> emitUnsignedCast(callSite, args)
            IntrinsicType.NOT -> emitNot(args)
            IntrinsicType.FROM_BITS -> emitReinterpret(callSite, args)
            IntrinsicType.TO_BITS -> emitReinterpret(callSite, args)
        }
        assert(result.type == callee.llvmReturnType) {
            "Substitution of ${callee.functionName} has wrong result type"
        }
        return result
    }

    private fun FunctionGenerationContext.emitReinterpret(callSite: IrCall, args: List<LLVMValueRef>) =
            bitcast(callSite.symbol.owner.llvmReturnType, args[0])

    private fun FunctionGenerationContext.emitNot(args: List<LLVMValueRef>) =
            not(args[0])
    
    private fun FunctionGenerationContext.emitPlus(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return if (first.type.isFloatingPoint()) {
            fadd(first, second)
        } else {
            add(first, second)
        }
    }

    private fun FunctionGenerationContext.emitSignedCast(callSite: IrCall, args: List<LLVMValueRef>) =
            cast(args[0], callSite.symbol.owner.llvmReturnType, signed=true)

    private fun FunctionGenerationContext.emitUnsignedCast(callSite: IrCall, args: List<LLVMValueRef>) =
            cast(args[0], callSite.symbol.owner.llvmReturnType, signed=false)

    private fun FunctionGenerationContext.emitShift(op: LLVMOpcode, args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        val shift = if (first.type == int64Type) {
            val tmp = and(second, Int32(63).llvm)
            zext(tmp, int64Type)
        } else {
            and(second, Int32(31).llvm)
        }
        return LLVMBuildBinOp(builder, op, first, shift, "")!!
    }

    private fun FunctionGenerationContext.emitShl(args: List<LLVMValueRef>) =
            emitShift(LLVMOpcode.LLVMShl, args)

    private fun FunctionGenerationContext.emitShr(args: List<LLVMValueRef>) =
            emitShift(LLVMOpcode.LLVMAShr, args)

    private fun FunctionGenerationContext.emitUshr(args: List<LLVMValueRef>) =
            emitShift(LLVMOpcode.LLVMLShr, args)

    private fun FunctionGenerationContext.emitAnd(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return and(first, second)
    }

    private fun FunctionGenerationContext.emitOr(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return or(first, second)
    }

    private fun FunctionGenerationContext.emitXor(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return xor(first, second)
    }

    private fun FunctionGenerationContext.emitInv(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val mask = makeConstOfType(first.type, -1)
        return xor(first, mask)
    }

    private fun FunctionGenerationContext.emitMinus(args: List<LLVMValueRef>): LLVMValueRef  {
        val (first, second) = args
        return if (first.type.isFloatingPoint()) {
            fsub(first, second)
        } else {
            sub(first, second)
        }
    }

    private fun FunctionGenerationContext.emitTimes(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFMul(builder, first, second, "")
        } else {
            LLVMBuildMul(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitThrowIfZero(divider: LLVMValueRef, exceptionHandler: ExceptionHandler) {
        ifThen(icmpEq(divider, Zero(divider.type).llvm)) {
            val throwArithExc = codegen.llvmFunction(context.ir.symbols.throwArithmeticException.owner)
            call(throwArithExc, emptyList(), Lifetime.GLOBAL, exceptionHandler)
            unreachable()
        }
    }

    private fun FunctionGenerationContext.emitSignedDiv(args: List<LLVMValueRef>, exceptionHandler: ExceptionHandler): LLVMValueRef {
        val (first, second) = args
        if (!second.type.isFloatingPoint()) {
            emitThrowIfZero(second, exceptionHandler)
        }
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFDiv(builder, first, second, "")
        } else {
            LLVMBuildSDiv(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitSignedRem(args: List<LLVMValueRef>, exceptionHandler: ExceptionHandler): LLVMValueRef {
        val (first, second) = args
        if (!second.type.isFloatingPoint()) {
            emitThrowIfZero(second, exceptionHandler)
        }
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFRem(builder, first, second, "")
        } else {
            LLVMBuildSRem(builder, first, second, "")
        }!!
    }

    private fun FunctionGenerationContext.emitUnsignedDiv(args: List<LLVMValueRef>, exceptionHandler: ExceptionHandler): LLVMValueRef {
        val (first, second) = args
        emitThrowIfZero(second, exceptionHandler)
        return LLVMBuildUDiv(builder, first, second, "")!!
    }

    private fun FunctionGenerationContext.emitUnsignedRem(args: List<LLVMValueRef>, exceptionHandler: ExceptionHandler): LLVMValueRef {
        val (first, second) = args
        emitThrowIfZero(second, exceptionHandler)
        return LLVMBuildURem(builder, first, second, "")!!
    }

    private fun FunctionGenerationContext.emitInc(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val const1 = makeConstOfType(first.type, 1)
        return if (first.type.isFloatingPoint()) {
            fadd(first, const1)
        } else {
            add(first, const1)
        }
    }

    private fun FunctionGenerationContext.emitDec(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val const1 = makeConstOfType(first.type, 1)
        return if (first.type.isFloatingPoint()) {
            fsub(first, const1)
        } else {
            sub(first, const1)
        }
    }

    private fun FunctionGenerationContext.emitUnaryPlus(args: List<LLVMValueRef>) =
            args[0]

    private fun FunctionGenerationContext.emitUnaryMinus(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val destTy = first.type
        val const0 = makeConstOfType(destTy, 0)
        return if (destTy.isFloatingPoint()) {
            fsub(const0, first)
        } else {
            sub(const0, first)
        }
    }

    private fun FunctionGenerationContext.emitCompareTo(args: List<LLVMValueRef>, signed: Boolean): LLVMValueRef {
        val (first, second) = args
        val equal: LLVMValueRef
        val less: LLVMValueRef
        if (first.type.isFloatingPoint()) {
            equal = fcmpEq(first, second)
            less = fcmpLt(first, second)
        } else {
            equal = icmpEq(first, second)
            less = if (signed) icmpLt(first, second) else icmpULt(first, second)
        }
        val tmp = select(less, Int32(-1).llvm, Int32(1).llvm)
        return select(equal, Int32(0).llvm, tmp)
    }

    private fun FunctionGenerationContext.emitSignedCompareTo(args: List<LLVMValueRef>) =
            emitCompareTo(args, signed=true)

    private fun FunctionGenerationContext.emitUnsignedCompareTo(args: List<LLVMValueRef>) =
            emitCompareTo(args, signed=false)

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