package com.goblin.swap.contract;

import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.JSONSerializable;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;
import org.rocksdb.ReadTier;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

public class GoblinSwap implements Contract {
    private String name;
    private String symbol;
    private int decimals;
    private Address token;
    private Address factory;
    private BigInteger _totalSupply = BigInteger.ZERO;
    private Map<Address, BigInteger> _balances = new HashMap<Address, BigInteger>();
    private Address lpToken;

    public GoblinSwap(String name, String symbol, int decimals, Address token, Address factory, Address lpToken) {
        this.name = name;
        this.symbol = symbol;
        this.decimals = decimals;
        this.token = token;
        this.factory = factory;
        this.lpToken = lpToken;
    }


    @View
    public BigInteger getTokenBalance(Address address) {
        String[][] args = new String[][]{new String[]{address.toString()}};
        BigInteger amount = new BigInteger(token.callWithReturnValue("balanceOf", null, args, null));
        return amount;
    }


    @View
    public BigInteger getInputPrice(BigInteger input_amount, BigInteger input_reserve, BigInteger output_reserve) {
        require(input_reserve.compareTo(BigInteger.ZERO) > 0 && output_reserve.compareTo(BigInteger.ZERO) > 0, "INVALID_VALUE");
        BigInteger input_amount_with_fee = input_amount.multiply(BigInteger.valueOf(997));
        BigInteger numerator = input_amount_with_fee.multiply(output_reserve);
        BigInteger denominator = input_reserve.multiply(BigInteger.valueOf(1000)).add(input_amount_with_fee);
        return numerator.divide(denominator);
    }


    @View
    public BigInteger getOutputPrice(BigInteger output_amount, BigInteger input_reserve, BigInteger output_reserve) {
        require(input_reserve.compareTo(BigInteger.ZERO) > 0 && output_reserve.compareTo(BigInteger.ZERO) > 0);
        BigInteger numerator = input_reserve.multiply(output_amount).multiply(BigInteger.valueOf(1000));
        BigInteger denominator = (output_reserve.subtract(output_amount)).multiply(BigInteger.valueOf(997));
        return (numerator.divide(denominator)).add(BigInteger.ONE);
    }


    private BigInteger nulsToTokenInput(BigInteger nuls_sold, BigInteger min_tokens, BigInteger deadline, Address buyer, Address recipient) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0 && nuls_sold.compareTo(BigInteger.ZERO) > 0 && min_tokens.compareTo(BigInteger.ZERO) > 0);
        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger tokens_bought = getInputPrice(nuls_sold, Msg.address().balance().subtract(nuls_sold), token_reserve);
        require(tokens_bought.compareTo(min_tokens) >= 0);
        String[][] args1 = new String[][]{new String[]{recipient.toString()}, new String[]{tokens_bought.toString()}};
        token.call("transfer", null, args1, null);

        emit(new TokenPurchase(buyer, nuls_sold, tokens_bought));
        emit(new Snapshot(buyer, Msg.address().balance(), getTokenBalance(Msg.address())));
        return tokens_bought;

    }

    @Payable
    public BigInteger nulsToTokenSwapInput(BigInteger min_tokens, BigInteger deadline) {
        return nulsToTokenInput(Msg.value(), min_tokens, deadline, Msg.sender(), Msg.sender());
    }


    @Payable
    public BigInteger nulsToTokenTransferInput(BigInteger min_tokens, BigInteger deadline, Address recipient) {
        require(recipient != null && !recipient.equals(Msg.address()));
        return nulsToTokenInput(Msg.value(), min_tokens, deadline, Msg.sender(), recipient);
    }


    private BigInteger nulsToTokenOutput(BigInteger tokens_bought, BigInteger max_nuls, BigInteger deadline, Address buyer, Address recipient) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0 && tokens_bought.compareTo(BigInteger.ZERO) > 0 && max_nuls.compareTo(BigInteger.ZERO) > 0);
        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger nuls_sold = getOutputPrice(tokens_bought, Msg.address().balance().subtract(max_nuls), token_reserve);
        BigInteger nuls_refund = max_nuls.subtract(nuls_sold);
        if (nuls_refund.compareTo(BigInteger.ZERO) > 0) {
            buyer.transfer(nuls_refund);
        }
        String[][] args = new String[][]{new String[]{recipient.toString()}, new String[]{tokens_bought.toString()}};
        token.call("transfer", null, args, null);
        emit(new TokenPurchase(buyer, nuls_sold, tokens_bought));
        emit(new Snapshot(buyer, Msg.address().balance(), getTokenBalance(Msg.address())));
        return nuls_sold;
    }

    @Payable
    public BigInteger nulsToTokenSwapOutput(BigInteger tokens_bought, BigInteger deadline) {
        return nulsToTokenOutput(tokens_bought, Msg.value(), deadline, Msg.sender(), Msg.sender());
    }

    @Payable
    public BigInteger nulsToTokenTransferOutput(BigInteger tokens_bought, BigInteger deadline, Address recipient) {
        require(recipient != null && !recipient.equals(Msg.address()));
        return nulsToTokenOutput(tokens_bought, Msg.value(), deadline, Msg.sender(), recipient);
    }


    private BigInteger tokenToNulsInput(BigInteger tokens_sold, BigInteger min_nuls, BigInteger deadline, Address buyer, Address recipient) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0 && tokens_sold.compareTo(BigInteger.ZERO) > 0 && min_nuls.compareTo(BigInteger.ZERO) > 0);

        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger nuls_bought = getInputPrice(tokens_sold, token_reserve, Msg.address().balance());
        BigInteger wei_bought = nuls_bought;
        require(wei_bought.compareTo(min_nuls) >= 0);
        recipient.transfer(wei_bought);

        String[][] args = new String[][]{new String[]{buyer.toString()}, new String[]{Msg.address().toString()}, new String[]{tokens_sold.toString()}};
        token.call("transferFrom", null, args, null);

        emit(new NulsPurchase(buyer, tokens_sold, wei_bought));
        emit(new Snapshot(buyer, Msg.address().balance(), getTokenBalance(Msg.address())));

        return wei_bought;
    }

    public BigInteger tokenToNulsSwapInput(BigInteger tokens_sold, BigInteger min_nuls, BigInteger deadline) {
        return tokenToNulsInput(tokens_sold, min_nuls, deadline, Msg.sender(), Msg.sender());
    }

    public BigInteger tokenToNulsTransferInput(BigInteger tokens_sold, BigInteger min_nuls, BigInteger deadline, Address recipient) {
        require(recipient != null && !recipient.equals(Msg.address()));
        return tokenToNulsInput(tokens_sold, min_nuls, deadline, Msg.sender(), recipient);
    }

    private BigInteger tokenToNulsOutput(BigInteger nuls_bought, BigInteger max_tokens, BigInteger deadline, Address buyer, Address recipient) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0 && nuls_bought.compareTo(BigInteger.ZERO) > 0);
        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger tokens_sold = getOutputPrice(nuls_bought, token_reserve, Msg.address().balance());
        require(max_tokens.compareTo(tokens_sold) >= 0);
        recipient.transfer(nuls_bought);
        String[][] args = new String[][]{new String[]{buyer.toString()}, new String[]{Msg.address().toString()}, new String[]{tokens_sold.toString()}};
        token.call("transferFrom", null, args, null);
        emit(new NulsPurchase(buyer, tokens_sold, nuls_bought));
        emit(new Snapshot(buyer, Msg.address().balance(), getTokenBalance(Msg.address())));
        return tokens_sold;
    }

    public BigInteger tokenToNulsSwapOutput(BigInteger nuls_bought, BigInteger max_tokens, BigInteger deadline) {
        return tokenToNulsOutput(nuls_bought, max_tokens, deadline, Msg.sender(), Msg.sender());
    }

    public BigInteger tokenToNulsTransferOutput(BigInteger nuls_bought, BigInteger max_tokens, BigInteger deadline, Address recipient) {
        require(recipient != null && !recipient.equals(Msg.address()));
        return tokenToNulsOutput(nuls_bought, max_tokens, deadline, Msg.sender(), recipient);
    }


    private BigInteger tokenToTokenInput(BigInteger tokens_sold, BigInteger min_tokens_bought, BigInteger min_nuls_bought, BigInteger deadline, Address buyer, Address recipient, Address exchange_addr) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0 && tokens_sold.compareTo(BigInteger.ZERO) > 0 && min_tokens_bought.compareTo(BigInteger.ZERO) > 0 && min_nuls_bought.compareTo(BigInteger.ZERO) > 0, "illegal input parameters");
        require(exchange_addr != null && !exchange_addr.equals(Msg.address()), "illegal exchange addr");

        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger nuls_bought = getInputPrice(tokens_sold, token_reserve, Msg.address().balance());
        BigInteger wei_bought = nuls_bought;

        require(wei_bought.compareTo(min_nuls_bought) >= 0, "min nuls bought not matched");
        String[][] args = new String[][]{new String[]{buyer.toString()}, new String[]{Msg.address().toString()}, new String[]{tokens_sold.toString()}};
        token.call("transferFrom", null, args, null);

        String[][] args1 = new String[][]{new String[]{min_tokens_bought.toString()}, new String[]{deadline.toString()}, new String[]{recipient.toString()}};
        BigInteger tokens_bought = new BigInteger(exchange_addr.callWithReturnValue("nulsToTokenTransferInput", null, args1, wei_bought));
        emit(new NulsPurchase(buyer, tokens_sold, wei_bought));
        emit(new Snapshot(buyer, Msg.address().balance(), getTokenBalance(Msg.address())));
        return tokens_bought;
    }

    public BigInteger tokenToTokenSwapInput(BigInteger tokens_sold, BigInteger min_tokens_bought, BigInteger min_nuls_bought, BigInteger deadline, Address token_addr) {
        String[][] args = new String[][]{new String[]{token_addr.toString()}};
        Address exchange_addr = new Address(factory.callWithReturnValue("getExchange", null, args, null));
        return tokenToTokenInput(tokens_sold, min_tokens_bought, min_nuls_bought, deadline, Msg.sender(), Msg.sender(), exchange_addr);
    }

    public BigInteger tokenToTokenTransferInput(BigInteger tokens_sold, BigInteger min_tokens_bought, BigInteger min_nuls_bought, BigInteger deadline, Address recipient, Address token_addr) {
        String[][] args = new String[][]{new String[]{token_addr.toString()}};
        Address exchange_addr = new Address(factory.callWithReturnValue("getExchange", null, args, null));
        return tokenToTokenInput(tokens_sold, min_tokens_bought, min_nuls_bought, deadline, Msg.sender(), recipient, exchange_addr);
    }

    private BigInteger tokenToTokenOutput(BigInteger tokens_bought, BigInteger max_tokens_sold, BigInteger max_nuls_sold, BigInteger deadline, Address buyer, Address recipient, Address exchange_addr) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) >= 0 && (tokens_bought.compareTo(BigInteger.ZERO) > 0 && max_nuls_sold.compareTo(BigInteger.ZERO) > 0), "illegal input parameters");
        require(exchange_addr != null && !exchange_addr.equals(Msg.address()));
        String[][] args = new String[][]{new String[]{tokens_bought.toString()}};
        BigInteger nuls_bought = new BigInteger(exchange_addr.callWithReturnValue("getNulsToTokenOutputPrice", null, args, null));
        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger tokens_sold = getOutputPrice(nuls_bought, token_reserve, Msg.address().balance());
        require(max_tokens_sold.compareTo(tokens_sold) >= 0 && max_nuls_sold.compareTo(nuls_bought) >= 0, "max token sold not matched");

        String[][] args1 = new String[][]{new String[]{buyer.toString()}, new String[]{Msg.address().toString()}, new String[]{tokens_sold.toString()}};
        token.call("transferFrom", null, args1, null);

        String[][] args2 = new String[][]{new String[]{tokens_bought.toString()}, new String[]{deadline.toString()}, new String[]{recipient.toString()}};
        BigInteger nuls_sold = new BigInteger(exchange_addr.callWithReturnValue("nulsToTokenTransferOutput", null, args2, nuls_bought));
        emit(new NulsPurchase(buyer, tokens_sold, nuls_bought));
        emit(new Snapshot(buyer, Msg.address().balance(), getTokenBalance(Msg.address())));
        return tokens_sold;
    }


    public BigInteger tokenToTokenSwapOutput(BigInteger tokens_bought, BigInteger max_tokens_sold, BigInteger max_nuls_sold, BigInteger deadline, Address token_addr) {
        String[][] args = new String[][]{new String[]{token_addr.toString()}};
        Address exchange_addr = new Address(factory.callWithReturnValue("getExchange", null, args, null));
        return tokenToTokenOutput(tokens_bought, max_tokens_sold, max_nuls_sold, deadline, Msg.sender(), Msg.sender(), exchange_addr);
    }

    public BigInteger tokenToTokenTransferOutput(BigInteger tokens_bought, BigInteger max_tokens_sold, BigInteger max_nuls_sold, BigInteger deadline, Address recipient, Address token_addr) {
        String[][] args = new String[][]{new String[]{token_addr.toString()}};
        Address exchange_addr = new Address(factory.callWithReturnValue("getExchange", null, args, null));
        return tokenToTokenOutput(tokens_bought, max_tokens_sold, max_nuls_sold, deadline, Msg.sender(), recipient, exchange_addr);
    }

    public BigInteger tokenToExchangeSwapInput(BigInteger tokens_sold, BigInteger min_tokens_bought, BigInteger min_nuls_bought, BigInteger deadline, Address exchange_addr) {
        return tokenToTokenInput(tokens_sold, min_tokens_bought, min_nuls_bought, deadline, Msg.sender(), Msg.sender(), exchange_addr);
    }


    public BigInteger tokenToExchangeTransferInput(BigInteger tokens_sold, BigInteger min_tokens_bought, BigInteger min_nuls_bought, BigInteger deadline, Address recipient, Address exchange_addr) {
        require(recipient != null && !recipient.equals(Msg.address()), "illegal recipient");
        return tokenToTokenInput(tokens_sold, min_tokens_bought, min_nuls_bought, deadline, Msg.sender(), recipient, exchange_addr);
    }


    public BigInteger tokenToExchangeSwapOutput(BigInteger tokens_bought, BigInteger max_tokens_sold, BigInteger max_nuls_sold, BigInteger deadline, Address exchange_addr) {
        return tokenToTokenOutput(tokens_bought, max_tokens_sold, max_nuls_sold, deadline, Msg.sender(), Msg.sender(), exchange_addr);
    }

    public BigInteger tokenToExchangeTransferOutput(BigInteger tokens_bought, BigInteger max_tokens_sold, BigInteger max_nuls_sold, BigInteger deadline, Address recipient, Address exchange_addr) {
        require(recipient != null && !recipient.equals(Msg.address()), "illegal recipient");
        return tokenToTokenOutput(tokens_bought, max_tokens_sold, max_nuls_sold, deadline, Msg.sender(), recipient, exchange_addr);
    }

    @View
    public BigInteger getNulsToTokenInputPrice(BigInteger nuls_sold) {
        require(nuls_sold.compareTo(BigInteger.ZERO) > 0, "nuls sold must greater than 0");
        BigInteger token_reserve = getTokenBalance(Msg.address());
        return getInputPrice(nuls_sold, Msg.address().balance(), token_reserve);
    }


    @View
    public BigInteger getTokenToNulsInputPrice(BigInteger tokens_sold) {
        require(tokens_sold.compareTo(BigInteger.ZERO) > 0, "tokens sold must greater than 0");
        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger nuls_bought = getInputPrice(tokens_sold, token_reserve, Msg.address().balance());
        return nuls_bought;
    }

    @View
    public BigInteger getTokenToNulsOutputPrice(BigInteger nuls_bought) {
        require(nuls_bought.compareTo(BigInteger.ZERO) > 0, "nuls bought must greater than 0");
        BigInteger token_reserve = getTokenBalance(Msg.address());
        return getOutputPrice(nuls_bought, token_reserve, Msg.address().balance());
    }


    @View
    public BigInteger getNulsToTokenOutputPrice(BigInteger tokens_bought) {
        require(tokens_bought.compareTo(BigInteger.ZERO) > 0, "tokens bought must greater than 0");
        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger nuls_sold = getOutputPrice(tokens_bought, Msg.address().balance(), token_reserve);
        return nuls_sold;
    }

    @View
    public BigInteger getTotalSupply() {
        return _totalSupply;
    }

    @View
    public BigInteger getLiquidity(BigInteger nulsAmount, BigInteger min_liquidity, BigInteger max_tokens, BigInteger deadline) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) > 0 && max_tokens.compareTo(BigInteger.ZERO) > 0 && nulsAmount.compareTo(BigInteger.ZERO) > 0, "INVALID_ARGUMENT");
        BigInteger total_liquidity = _totalSupply;
        if (total_liquidity.compareTo(BigInteger.ZERO) > 0) {
            require(min_liquidity.compareTo(BigInteger.ZERO) > 0, "min_liquidity must greater than 0");
            BigInteger nuls_reserve = Msg.address().balance();
            BigInteger token_reserve = getTokenBalance(Msg.address());
            BigInteger token_amount = (nulsAmount.multiply(token_reserve).divide(nuls_reserve)).add(BigInteger.ONE);
            BigInteger liquidity_minted = nulsAmount.multiply(total_liquidity).divide(nuls_reserve);
            require(max_tokens.compareTo(token_amount) >= 0 && liquidity_minted.compareTo(min_liquidity) >= 0, "max tokens not meet or liquidity_minted not meet min_liquidity");


            return liquidity_minted;
        } else {
            require(factory != null && token != null && nulsAmount.compareTo(BigInteger.valueOf(10000000)) >= 0, "INVALID_VALUE");
            BigInteger initial_liquidity = Msg.address().balance();
            return initial_liquidity;
        }
    }


    @Payable
    public BigInteger addLiquidity(BigInteger min_liquidity, BigInteger max_tokens, BigInteger deadline) {
        require(deadline.compareTo(BigInteger.valueOf(Block.timestamp())) > 0 && max_tokens.compareTo(BigInteger.ZERO) > 0 && Msg.value().compareTo(BigInteger.ZERO) > 0, "INVALID_ARGUMENT");
        BigInteger total_liquidity = _totalSupply;
        if (total_liquidity.compareTo(BigInteger.ZERO) > 0) {
            require(min_liquidity.compareTo(BigInteger.ZERO) > 0, "min_liquidity must greater than 0");
            BigInteger nuls_reserve = Msg.address().balance().subtract(Msg.value());
            BigInteger token_reserve = getTokenBalance(Msg.address());
            BigInteger token_amount = (Msg.value().multiply(token_reserve).divide(nuls_reserve)).add(BigInteger.ONE);
            BigInteger liquidity_minted = Msg.value().multiply(total_liquidity).divide(nuls_reserve);
            require(max_tokens.compareTo(token_amount) >= 0 && liquidity_minted.compareTo(min_liquidity) >= 0, "max tokens not meet or liquidity_minted not meet min_liquidity");
            if (_balances.get(Msg.sender()) != null) {
                _balances.put(Msg.sender(), _balances.get(Msg.sender()).add(liquidity_minted));
            } else {
                _balances.put(Msg.sender(), liquidity_minted);
            }
            _totalSupply = total_liquidity.add(liquidity_minted);
            String[][] args1 = new String[][]{new String[]{Msg.sender().toString()}, new String[]{Msg.address().toString()}, new String[]{token_amount.toString()}};
            token.call("transferFrom", null, args1, null);
            String[][] args2 = new String[][]{new String[]{Msg.sender().toString()}, new String[]{liquidity_minted.toString()}, new String[]{Msg.value().toString()}, new String[]{token_amount.toString()}};
            lpToken.call("addLiquidity", null, args2, null);

            emit(new AddLiquidity(Msg.sender(), Msg.value(), token_amount));
            emit(new Snapshot(Msg.sender(), Msg.address().balance(), getTokenBalance(Msg.address())));
            emit(new TransferEvent(null, Msg.sender(), liquidity_minted));
            return liquidity_minted;
        } else {
            require(factory != null && token != null && Msg.value().compareTo(BigInteger.valueOf(10000000)) >= 0, "INVALID_VALUE");
            String[][] args = new String[][]{new String[]{token.toString()}};
            Address exchange_addr = new Address(factory.callWithReturnValue("getExchange", null, args, null));
            require(exchange_addr.equals(Msg.address()), "token address not meet exchange");
            BigInteger token_amount = max_tokens;
            BigInteger initial_liquidity = Msg.address().balance();
            _totalSupply = initial_liquidity;
            _balances.put(Msg.sender(), initial_liquidity);

            String[][] args1 = new String[][]{new String[]{Msg.sender().toString()}, new String[]{Msg.address().toString()}, new String[]{token_amount.toString()}};
            token.call("transferFrom", null, args1, null);


            String[][] args2 = new String[][]{new String[]{Msg.sender().toString()}, new String[]{initial_liquidity.toString()}, new String[]{Msg.value().toString()}, new String[]{token_amount.toString()}};
            lpToken.call("addLiquidity", null, args2, null);

            emit(new AddLiquidity(Msg.sender(), Msg.value(), token_amount));
            emit(new Snapshot(Msg.sender(), Msg.address().balance(), getTokenBalance(Msg.address())));
            emit(new TransferEvent(null, Msg.sender(), initial_liquidity));
            return initial_liquidity;
        }
    }


    public void removeLiquidity(BigInteger amount, BigInteger min_nuls, BigInteger min_tokens, BigInteger deadline) {
        require(amount.compareTo(BigInteger.ZERO) > 0 && deadline.compareTo(BigInteger.valueOf(Block.timestamp())) > 0 && min_nuls.compareTo(BigInteger.ZERO) > 0 && min_tokens.compareTo(BigInteger.ZERO) > 0, "illegal input parameters");
        BigInteger total_liquidity = _totalSupply;
        require(total_liquidity.compareTo(BigInteger.ZERO) > 0, "total_liquidity must greater than 0");
        BigInteger token_reserve = getTokenBalance(Msg.address());
        BigInteger nuls_amount = amount.multiply(Msg.address().balance()).divide(total_liquidity);
        BigInteger token_amount = amount.multiply(token_reserve).divide(total_liquidity);
        require(nuls_amount.compareTo(min_nuls) >= 0 && token_amount.compareTo(min_tokens) >= 0, "min_token or min_nuls not meet");
        String[][] a = new String[][]{new String[]{Msg.sender().toString()}};
        BigInteger canUseLp = new BigInteger(lpToken.callWithReturnValue("getCanUsedLpAmount", null, a, null));

        require(_balances.get(Msg.sender()) != null && canUseLp.compareTo(amount) >= 0, "can used lp amount is not enough");
        BigInteger lastAmount = _balances.get(Msg.sender()).subtract(amount);
        _balances.put(Msg.sender(), lastAmount.compareTo(BigInteger.ZERO) > 0 ? lastAmount : BigInteger.ZERO);
        _totalSupply = total_liquidity.subtract(amount);
        Msg.sender().transfer(nuls_amount);
        String[][] args = new String[][]{new String[]{Msg.sender().toString()}, new String[]{token_amount.toString()}};
        token.call("transfer", null, args, null);
        String[][] args1 = new String[][]{new String[]{Msg.sender().toString()}, new String[]{amount.toString()}, new String[]{nuls_amount.toString()}, new String[]{token_amount.toString()}};
        lpToken.call("removeLiquidity", null, args1, null);

        emit(new RemoveLiquidity(Msg.sender(), nuls_amount, token_amount));
        emit(new Snapshot(Msg.sender(), Msg.address().balance(), getTokenBalance(Msg.address())));
        emit(new TransferEvent(Msg.sender(), null, amount));

    }


    class TokenPurchase implements Event {
        private Address buyer;
        private BigInteger nuls_sold;
        private BigInteger tokens_bought;

        public TokenPurchase(Address buyer, BigInteger nuls_sold, BigInteger tokens_bought) {
            this.buyer = buyer;
            this.nuls_sold = nuls_sold;
            this.tokens_bought = tokens_bought;
        }

        public Address getBuyer() {
            return buyer;
        }

        public void setBuyer(Address buyer) {
            this.buyer = buyer;
        }

        public BigInteger getnuls_sold() {
            return nuls_sold;
        }

        public void setnuls_sold(BigInteger nuls_sold) {
            this.nuls_sold = nuls_sold;
        }

        public BigInteger getTokens_bought() {
            return tokens_bought;
        }

        public void setTokens_bought(BigInteger tokens_bought) {
            this.tokens_bought = tokens_bought;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TokenPurchase that = (TokenPurchase) o;

            if (buyer != null ? !buyer.equals(that.buyer) : that.buyer != null) return false;
            if (nuls_sold != null ? !nuls_sold.equals(that.nuls_sold) : that.nuls_sold != null) return false;
            return tokens_bought != null ? tokens_bought.equals(that.tokens_bought) : that.tokens_bought == null;
        }

        @Override
        public int hashCode() {
            int result = buyer != null ? buyer.hashCode() : 0;
            result = 31 * result + (nuls_sold != null ? nuls_sold.hashCode() : 0);
            result = 31 * result + (tokens_bought != null ? tokens_bought.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "TokenPurchase{" +
                    "buyer=" + buyer +
                    ", nuls_sold=" + nuls_sold +
                    ", tokens_bought=" + tokens_bought +
                    '}';
        }
    }

    class NulsPurchase implements Event {
        private Address buyer;
        private BigInteger tokens_sold;
        private BigInteger nuls_bought;

        public NulsPurchase(Address buyer, BigInteger tokens_sold, BigInteger nuls_bought) {
            this.buyer = buyer;
            this.tokens_sold = tokens_sold;
            this.nuls_bought = nuls_bought;
        }

        public Address getBuyer() {
            return buyer;
        }

        public void setBuyer(Address buyer) {
            this.buyer = buyer;
        }

        public BigInteger getTokens_sold() {
            return tokens_sold;
        }

        public void setTokens_sold(BigInteger tokens_sold) {
            this.tokens_sold = tokens_sold;
        }

        public BigInteger getnuls_bought() {
            return nuls_bought;
        }

        public void setnuls_bought(BigInteger nuls_bought) {
            this.nuls_bought = nuls_bought;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            NulsPurchase that = (NulsPurchase) o;

            if (buyer != null ? !buyer.equals(that.buyer) : that.buyer != null) return false;
            if (tokens_sold != null ? !tokens_sold.equals(that.tokens_sold) : that.tokens_sold != null) return false;
            return nuls_bought != null ? nuls_bought.equals(that.nuls_bought) : that.nuls_bought == null;
        }

        @Override
        public int hashCode() {
            int result = buyer != null ? buyer.hashCode() : 0;
            result = 31 * result + (tokens_sold != null ? tokens_sold.hashCode() : 0);
            result = 31 * result + (nuls_bought != null ? nuls_bought.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "nulsPurchase{" +
                    "buyer=" + buyer +
                    ", tokens_sold=" + tokens_sold +
                    ", nuls_bought=" + nuls_bought +
                    '}';
        }
    }

    class AddLiquidity implements Event {
        private Address provider;
        private BigInteger nuls_amount;
        private BigInteger token_amount;

        public AddLiquidity(Address provider, BigInteger nuls_amount, BigInteger token_amount) {
            this.provider = provider;
            this.nuls_amount = nuls_amount;
            this.token_amount = token_amount;
        }

        public Address getProvider() {
            return provider;
        }

        public void setProvider(Address provider) {
            this.provider = provider;
        }

        public BigInteger getnuls_amount() {
            return nuls_amount;
        }

        public void setnuls_amount(BigInteger nuls_amount) {
            this.nuls_amount = nuls_amount;
        }

        public BigInteger getToken_amount() {
            return token_amount;
        }

        public void setToken_amount(BigInteger token_amount) {
            this.token_amount = token_amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AddLiquidity that = (AddLiquidity) o;

            if (provider != null ? !provider.equals(that.provider) : that.provider != null) return false;
            if (nuls_amount != null ? !nuls_amount.equals(that.nuls_amount) : that.nuls_amount != null) return false;
            return token_amount != null ? token_amount.equals(that.token_amount) : that.token_amount == null;
        }

        @Override
        public int hashCode() {
            int result = provider != null ? provider.hashCode() : 0;
            result = 31 * result + (nuls_amount != null ? nuls_amount.hashCode() : 0);
            result = 31 * result + (token_amount != null ? token_amount.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "AddLiquidity{" +
                    "provider=" + provider +
                    ", nuls_amount=" + nuls_amount +
                    ", token_amount=" + token_amount +
                    '}';
        }
    }

    class RemoveLiquidity implements Event {
        private Address provider;
        private BigInteger nuls_amount;
        private BigInteger token_amount;

        public RemoveLiquidity(Address provider, BigInteger nuls_amount, BigInteger token_amount) {
            this.provider = provider;
            this.nuls_amount = nuls_amount;
            this.token_amount = token_amount;
        }

        public Address getProvider() {
            return provider;
        }

        public void setProvider(Address provider) {
            this.provider = provider;
        }

        public BigInteger getnuls_amount() {
            return nuls_amount;
        }

        public void setnuls_amount(BigInteger nuls_amount) {
            this.nuls_amount = nuls_amount;
        }

        public BigInteger getToken_amount() {
            return token_amount;
        }

        public void setToken_amount(BigInteger token_amount) {
            this.token_amount = token_amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RemoveLiquidity that = (RemoveLiquidity) o;

            if (provider != null ? !provider.equals(that.provider) : that.provider != null) return false;
            if (nuls_amount != null ? !nuls_amount.equals(that.nuls_amount) : that.nuls_amount != null) return false;
            return token_amount != null ? token_amount.equals(that.token_amount) : that.token_amount == null;
        }

        @Override
        public int hashCode() {
            int result = provider != null ? provider.hashCode() : 0;
            result = 31 * result + (nuls_amount != null ? nuls_amount.hashCode() : 0);
            result = 31 * result + (token_amount != null ? token_amount.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "RemoveLiquidity{" +
                    "provider=" + provider +
                    ", nuls_amount=" + nuls_amount +
                    ", token_amount=" + token_amount +
                    '}';
        }
    }


    class Snapshot implements Event {
        private Address operator;
        private BigInteger nuls_balance;
        private BigInteger token_balance;

        public Snapshot(Address operator, BigInteger nuls_balance, BigInteger token_balance) {
            this.operator = operator;
            this.nuls_balance = nuls_balance;
            this.token_balance = token_balance;
        }

        public Address getOperator() {
            return operator;
        }

        public void setOperator(Address operator) {
            this.operator = operator;
        }

        public BigInteger getnuls_balance() {
            return nuls_balance;
        }

        public void setnuls_balance(BigInteger nuls_balance) {
            this.nuls_balance = nuls_balance;
        }

        public BigInteger getToken_balance() {
            return token_balance;
        }

        public void setToken_balance(BigInteger token_balance) {
            this.token_balance = token_balance;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Snapshot that = (Snapshot) o;

            if (operator != null ? !operator.equals(that.operator) : that.operator != null) return false;
            if (nuls_balance != null ? !nuls_balance.equals(that.nuls_balance) : that.nuls_balance != null)
                return false;
            return token_balance != null ? token_balance.equals(that.token_balance) : that.token_balance == null;
        }

        @Override
        public int hashCode() {
            int result = operator != null ? operator.hashCode() : 0;
            result = 31 * result + (nuls_balance != null ? nuls_balance.hashCode() : 0);
            result = 31 * result + (token_balance != null ? token_balance.hashCode() : 0);
            return result;
        }


        @Override
        public String toString() {
            return "Snapshot{" +
                    "operator=" + operator +
                    ", nuls_balance=" + nuls_balance +
                    ", token_balance=" + token_balance +
                    '}';
        }
    }

    class TransferEvent implements Event {

        private Address from;

        private Address to;

        private BigInteger value;

        public TransferEvent(Address from, @Required Address to, @Required BigInteger value) {
            this.from = from;
            this.to = to;
            this.value = value;
        }

        public Address getFrom() {
            return from;
        }

        public void setFrom(Address from) {
            this.from = from;
        }

        public Address getTo() {
            return to;
        }

        public void setTo(Address to) {
            this.to = to;
        }

        public BigInteger getValue() {
            return value;
        }

        public void setValue(BigInteger value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TransferEvent that = (TransferEvent) o;

            if (from != null ? !from.equals(that.from) : that.from != null) return false;
            if (to != null ? !to.equals(that.to) : that.to != null) return false;
            return value != null ? value.equals(that.value) : that.value == null;
        }

        @Override
        public int hashCode() {
            int result = from != null ? from.hashCode() : 0;
            result = 31 * result + (to != null ? to.hashCode() : 0);
            result = 31 * result + (value != null ? value.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "TransferEvent{" +
                    "from=" + from +
                    ", to=" + to +
                    ", value=" + value +
                    '}';
        }

    }
}
